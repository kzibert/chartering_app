package com.chartering.service.mail;

import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.CampaignRecipientRequest;
import com.chartering.exception.MailNotConfiguredException;
import com.chartering.service.MailTemplateService;
import com.chartering.service.SettingsService.CirculationSettings;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The original flow: each circular is handed to the user's own mailbox over SMTP, one
 * message per recipient, exactly as if it had been sent by hand.
 *
 * <p>What this buys is that the mail genuinely comes from a person's mailbox — the same
 * address the recipient already corresponds with, with replies landing where they expect.
 * What it costs is that the mailbox's reputation and quota are now the campaign's, which is
 * why the pacing around it is slow and the circuit breaker is quick.
 */
@Component
@RequiredArgsConstructor
public class SmtpCircularSender implements CircularSender {

    /** A 5xx reply is a permanent refusal — retrying it wastes quota and looks worse. */
    private static final Pattern PERMANENT_SMTP_ERROR = Pattern.compile("\\b5\\d\\d\\b");

    private final SmtpTransport transport;
    private final MailCampaignProperties props;
    private final MailTemplateService templates;

    @Override
    public CircularProvider provider() {
        return CircularProvider.SMTP;
    }

    @Override
    public List<String> missingSettings(CirculationSettings cfg) {
        List<String> missing = new ArrayList<>();
        // Host comes from Settings, which falls back to MAIL_HOST when it was never changed.
        if (!isSet(cfg.smtpHost())) {
            missing.add("SMTP host (Settings, or MAIL_HOST)");
        }
        if (!transport.hasUsername()) {
            missing.add("MAIL_USERNAME");
        }
        if (!transport.hasPassword()) {
            missing.add("MAIL_PASSWORD");
        }
        if (!isSet(cfg.fromAddress())) {
            missing.add("From address (Settings, or MAIL_FROM)");
        }
        return missing;
    }

    @Override
    public Bound bind(CirculationSettings cfg) {
        return new BoundSmtp(transport.senderFor(cfg), cfg);
    }

    /** The mailbox the app authenticates as, for the config screen. Never the password. */
    public String username() {
        return transport.username();
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    /** One run's SMTP transport, frozen against the settings the campaign started with. */
    private final class BoundSmtp implements Bound {

        private final JavaMailSenderImpl sender;
        private final CirculationSettings cfg;

        private BoundSmtp(JavaMailSenderImpl sender, CirculationSettings cfg) {
            this.sender = sender;
            this.cfg = cfg;
        }

        @Override
        public CircularProvider provider() {
            return CircularProvider.SMTP;
        }

        @Override
        public void verify() {
            if (sender == null) {
                return;
            }
            try {
                sender.testConnection();
            } catch (Exception e) {
                throw new MailNotConfiguredException("Could not connect to %s:%d — %s"
                        .formatted(sender.getHost(), sender.getPort(),
                                CircularSendException.rootMessage(e)));
            }
        }

        /** Build and hand one personalised message to the transport. */
        @Override
        public void send(CampaignRecipientRequest r, String subjectTemplate, String htmlTemplate) {
            MimeMessage mime = sender.createMimeMessage();
            try {
                mime.setFrom(new InternetAddress(cfg.fromAddress(), cfg.fromName(), "UTF-8"));
                mime.setRecipient(Message.RecipientType.TO, new InternetAddress(r.getEmail().trim()));
                mime.setSubject(templates.renderText(subjectTemplate, r), "UTF-8");
                if (isSet(props.getReplyTo())) {
                    mime.setReplyTo(new Address[]{new InternetAddress(props.getReplyTo().trim())});
                }

                String html = templates.renderHtml(htmlTemplate, r);
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(templates.htmlToText(html), "UTF-8");
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(html, "text/html; charset=UTF-8");

                // A bare multipart/alternative, built by hand rather than via MimeMessageHelper:
                // the helper always nests mixed > related > alternative, and a multipart/mixed
                // carrying no attachment is both wasteful and a mild spam signal. Text part
                // first — alternative orders parts least- to most-preferred.
                MimeMultipart alternative = new MimeMultipart("alternative");
                alternative.addBodyPart(textPart);
                alternative.addBodyPart(htmlPart);
                mime.setContent(alternative);

                if (isSet(props.getUnsubscribeMailto())) {
                    mime.setHeader("List-Unsubscribe",
                            "<mailto:%s>".formatted(props.getUnsubscribeMailto().trim()));
                }
                sender.send(mime);
            } catch (MessagingException | UnsupportedEncodingException e) {
                // A message that cannot even be built will not build on a second attempt
                // either: the address or the template is malformed, not the connection.
                throw CircularSendException.permanent(
                        "Could not build the message for " + r.getEmail() + ": " + e.getMessage(), e);
            } catch (MailAuthenticationException e) {
                throw CircularSendException.auth(CircularSendException.rootMessage(e), e);
            } catch (MailException e) {
                // The reply code buried in the message is the only classification SMTP offers.
                String message = CircularSendException.rootMessage(e);
                throw PERMANENT_SMTP_ERROR.matcher(String.valueOf(message)).find()
                        ? CircularSendException.permanent(message, e)
                        : CircularSendException.transientFailure(message, e);
            }
        }
    }
}
