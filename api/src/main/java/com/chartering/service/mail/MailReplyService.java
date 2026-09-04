package com.chartering.service.mail;

import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.CampaignRecipientRequest;
import com.chartering.dto.EmailFooterResponse;
import com.chartering.dto.MailReplyRequest;
import com.chartering.dto.MailReplyResponse;
import com.chartering.exception.MailNotConfiguredException;
import com.chartering.exception.MailSendFailedException;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.MailMessage;
import com.chartering.model.MailReply;
import com.chartering.model.Person;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.MailReplyRepository;
import com.chartering.service.EmailFooterService;
import com.chartering.service.HtmlSanitizer;
import com.chartering.service.MailTemplateService;
import com.chartering.service.SettingsService;
import com.chartering.service.SettingsService.CirculationSettings;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Answering a message in the mailbox, from the app.
 *
 * <h2>Always through the mailbox, never through Brevo</h2>
 * <p>The Circulars tab can send either way, and this deliberately cannot. A reply belongs to
 * a conversation the correspondent started: it has to come from the address they wrote to,
 * land in their thread, and sit in the Sent folder of the mailbox whose owner will be asked
 * about it next week. Brevo is bulk infrastructure with its own reputation and its own
 * envelope — right for two hundred cold circulars, wrong for one answer to one broker. So
 * this reads the same SMTP settings the mailbox flow uses, even while circulars are going
 * out through Brevo.
 *
 * <h2>What is stored, and what is not</h2>
 * <p>A reply that goes out is written to {@code mail_replies} — see {@link MailReply} for
 * why that is not a row in {@code mail_messages}. A reply that fails is not stored at all:
 * it is an error on the screen of the person who wrote it, with their text still in the box,
 * and a table of messages that never left is not history anybody reads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailReplyService {

    /** How the quoted block introduces what it is quoting. */
    private static final DateTimeFormatter QUOTED_ON =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private final MailMessageRepository messages;
    private final MailReplyRepository replies;
    private final EmailFooterService footers;
    private final MailTemplateService templates;
    private final HtmlSanitizer sanitizer;
    private final SmtpTransport transport;
    private final SmtpCircularSender smtp;
    private final SettingsService settings;
    private final MailCampaignProperties props;

    /**
     * Compose, send and record one reply.
     *
     * <p>The row is written after the provider has accepted the message, not before. The
     * ordering is the whole point: this way the worst case is a reply that was sent and not
     * recorded — visible, and one line short in a count — rather than a record of a message
     * that never left, which nothing on any screen would ever contradict.
     */
    @Transactional
    public MailReplyResponse reply(Long messageId, MailReplyRequest req) {
        MailMessage original = messages.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", messageId));

        if (!props.isEnabled()) {
            throw new MailNotConfiguredException(
                    "Sending is switched off on this server (MAIL_ENABLED), so nothing can go "
                            + "out from here. The reply has not been sent.");
        }

        CirculationSettings cfg = settings.circulation();
        List<String> missing = smtp.missingSettings(cfg);
        if (!missing.isEmpty()) {
            throw new MailNotConfiguredException(
                    "The mailbox is not fully configured, so the reply was not sent. Still "
                            + "needed: " + String.join(", ", missing) + ".");
        }
        JavaMailSenderImpl sender = transport.senderFor(cfg);
        if (sender == null) {
            throw new MailNotConfiguredException(
                    "No SMTP transport is configured on this server, so the reply was not sent.");
        }

        EmailFooterResponse footer = req.getFooterId() == null ? null : footers.get(req.getFooterId());
        String composed = compose(req, original, footer);
        // The same merge circulars get, against the person this message is already linked to.
        // A footer from the library may well hold {{greeting}} or {{company}}, and it has to
        // mean here what it means there, or the two would be one library in name only.
        String html = templates.renderHtml(composed, recipientFor(original, req.getTo()));

        String from = replyFromAddress(cfg);
        String ourMessageId = messageIdFor(from);
        send(sender, cfg, from, req, original, html, ourMessageId);

        MailReply record = new MailReply();
        record.setMailMessage(original);
        record.setMessageId(ourMessageId);
        record.setToAddress(req.getTo().trim());
        record.setSubject(req.getSubject().trim());
        record.setBodyHtml(html);
        record.setFooterId(footer == null ? null : footer.id());
        record.setFooterName(footer == null ? null : footer.name());
        record.setSentAt(LocalDateTime.now());
        record.setSentBy(currentUser());
        replies.save(record);

        log.info("Replied to message {}: \"{}\" to {}", messageId, req.getSubject(), req.getTo());
        return new MailReplyResponse(record.getId(), original.getId(), record.getToAddress(),
                record.getSubject(), record.getFooterName(), record.getSentAt());
    }

    /** When this message was last answered from here, or null. */
    @Transactional(readOnly = true)
    public LocalDateTime lastRepliedAt(Long messageId) {
        return replies.lastRepliedAt(messageId);
    }

    // ---------------------------------------------------------------- composing

    /** Body, then footer, then the quoted original — the order a mail client writes them in. */
    private String compose(MailReplyRequest req, MailMessage original, EmailFooterResponse footer) {
        StringBuilder html = new StringBuilder(sanitizer.clean(req.getBodyHtml()));
        if (footer != null) {
            html.append(sanitizer.clean(footer.html()));
        }
        if (req.getIncludeOriginal() == null || req.getIncludeOriginal()) {
            html.append(quote(original));
        }
        return html.toString();
    }

    /**
     * The message being answered, indented underneath.
     *
     * <p>Quoted as the markup it arrived as rather than flattened to text: a broker's
     * position list is a table, and quoting it as a wall of run-together words is worse than
     * not quoting it. It goes through the sanitizer on the way out — the stored copy is
     * exactly what arrived, which is right for an archive and wrong for something this app
     * is about to put its own name on.
     */
    private String quote(MailMessage m) {
        String when = m.getReceivedAt() == null ? "" : m.getReceivedAt().format(QUOTED_ON) + ", ";
        String who = m.getFromName() == null || m.getFromName().isBlank()
                ? escape(m.getFromAddress())
                : escape(m.getFromName()) + " &lt;" + escape(m.getFromAddress()) + "&gt;";
        String body = m.getBodyHtml() != null && !m.getBodyHtml().isBlank()
                ? sanitizer.clean(m.getBodyHtml())
                : "<pre style=\"white-space:pre-wrap;font-family:inherit\">"
                        + escape(m.getBodyText() == null ? "" : m.getBodyText()) + "</pre>";

        return "<br><div style=\"color:#6b6b6b\">On " + when + who + " wrote:</div>"
                + "<blockquote style=\"margin:0 0 0 8px;padding-left:10px;"
                + "border-left:2px solid #d9d9d9;color:#4a4a4a\">" + body + "</blockquote>";
    }

    /**
     * The merge fields for this correspondent.
     *
     * <p>Read off the link the mailbox already made — the contact the sender's address
     * matched, and through it the person and the company. An unlinked message leaves them
     * empty, which is the case the placeholders already fall back from.
     */
    private CampaignRecipientRequest recipientFor(MailMessage m, String to) {
        Contact ct = m.getContact();
        Person p = m.getPerson();
        Company c = m.getCompany();
        // The contact's own greeting wins over the person's, exactly as it does everywhere
        // else — see DtoMapper. A greeting set on one address is set about that address.
        String greeting = ct != null && ct.getGreetingName() != null && !ct.getGreetingName().isBlank()
                ? ct.getGreetingName()
                : p == null ? null : p.getGreetingName();
        return new CampaignRecipientRequest(
                to.trim(),
                ct == null ? null : ct.getId(),
                greeting,
                p != null ? p.getFullName() : m.getFromName(),
                p == null ? null : p.getTitle(),
                c == null ? null : c.getName());
    }

    // ---------------------------------------------------------------- sending

    /**
     * Which of our addresses a reply comes from: the mailbox itself, not the circulars
     * From.
     *
     * <p>They are often different — a desk sends circulars as {@code desk@} while the
     * mailbox it reads is {@code chartering@} — and for a reply the mailbox is the right
     * one twice over. It is the address the correspondent wrote to, so the answer comes
     * back from where they sent it; and it is the address their own hand-written replies
     * already carry, so nothing about the thread changes because the answer was typed here
     * instead of in Outlook. The circulars address stays the fallback for a setup that
     * configures no mailbox user at all.
     */
    private String replyFromAddress(CirculationSettings cfg) {
        String mailbox = transport.username();
        // Only when it is actually an address. On Zoho and most mailbox providers the SMTP
        // username *is* the mailbox, which is the whole reason this prefers it — but a relay
        // is entitled to authenticate on a bare login ("desk01", an API key id), and putting
        // that in a From header builds a message no server will accept. The refusal that
        // came back would be about the envelope, not about the login, so it would send
        // whoever read it looking in the wrong place.
        return isAddress(mailbox) ? mailbox : cfg.fromAddress();
    }

    private void send(JavaMailSenderImpl sender, CirculationSettings cfg, String from,
                      MailReplyRequest req, MailMessage original, String html,
                      String ourMessageId) {
        try {
            // Our own Message-ID, by overriding the one JavaMail would invent at
            // saveChanges(). Knowing it is what lets the provider's Sent-folder copy be
            // recognised as this reply when it syncs back, instead of read as a second
            // message the mailbox sent.
            MimeMessage mime = new MimeMessage(sender.getSession()) {
                @Override
                protected void updateMessageID() throws MessagingException {
                    setHeader("Message-ID", ourMessageId);
                }
            };
            mime.setFrom(new InternetAddress(from, cfg.fromName(), "UTF-8"));
            mime.setRecipient(Message.RecipientType.TO, new InternetAddress(req.getTo().trim()));
            mime.setSubject(req.getSubject().trim(), "UTF-8");
            // No Reply-To, deliberately, though circulars set one. MAIL_REPLY_TO exists
            // because a circular may go out from an address that is not read; this one goes
            // out from the mailbox the conversation is already in, and pointing the answer
            // somewhere else would move a live thread out of it.

            // What makes this a reply rather than a new message with a familiar subject: the
            // recipient's client threads on these headers, not on "Re:". References carries
            // only the message answered — the chain above it lives in headers the sync does
            // not store, and a short true chain threads where a guessed one does not.
            if (isSet(original.getMessageId())) {
                mime.setHeader("In-Reply-To", original.getMessageId());
                mime.setHeader("References", original.getMessageId());
            }

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(templates.htmlToText(html), "UTF-8");
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(html, "text/html; charset=UTF-8");
            // multipart/alternative, text part first — least- to most-preferred, the same
            // shape the circular sender builds and for the same reasons.
            MimeMultipart alternative = new MimeMultipart("alternative");
            alternative.addBodyPart(textPart);
            alternative.addBodyPart(htmlPart);
            mime.setContent(alternative);

            // No List-Unsubscribe header here. It belongs on bulk mail; on an answer to a
            // message somebody sent us it offers to unsubscribe them from a correspondence.
            sender.send(mime);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            // Named endpoint, deliberately. A reply goes out over SMTP whatever the Circulars
            // tab is set to, so the host it used is not the one the screen was last showing —
            // and the host comes from Settings while the credentials come from the
            // environment, so the two can be made to disagree by changing one of them. Told
            // only that "the mail server would not send this", the first guess is the message;
            // told which account tried to talk to which host, an authentication failure
            // against a host somebody repointed reads as exactly that.
            throw new MailSendFailedException(
                    "The mail server would not send this reply. %s:%d, as %s, said: %s"
                            .formatted(sender.getHost(), sender.getPort(),
                                    isSet(sender.getUsername()) ? sender.getUsername() : "no user",
                                    CircularSendException.rootMessage(e)), e);
        }
    }

    /**
     * A Message-ID in the sending domain. The domain half is not cosmetic — an id whose
     * right-hand side does not belong to the sender is a cheap spam-filter signal.
     */
    private static String messageIdFor(String fromAddress) {
        int at = fromAddress == null ? -1 : fromAddress.indexOf('@');
        String domain = at > 0 ? fromAddress.substring(at + 1) : "chartering.local";
        return "<reply." + UUID.randomUUID() + "@" + domain + ">";
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? null : auth.getName();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Enough of a check to keep a non-address out of a From header, and no more. Real
     * address validation belongs to the {@code @Email} constraint on what the user typed;
     * this only asks whether a configured SMTP username is the mailbox or a bare login.
     */
    private static boolean isAddress(String s) {
        if (!isSet(s)) {
            return false;
        }
        int at = s.indexOf('@');
        return at > 0 && at < s.length() - 1 && s.indexOf(' ') < 0;
    }
}
