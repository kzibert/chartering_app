package com.chartering.service;

import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.CampaignConfigResponse;
import com.chartering.dto.CampaignRecipientRequest;
import com.chartering.dto.CampaignRequest;
import com.chartering.dto.CampaignStatusResponse;
import com.chartering.exception.MailNotConfiguredException;
import com.chartering.repository.ContactRepository;
import jakarta.annotation.PreDestroy;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Sends a circular to a list of recipients, one message per recipient.
 *
 * <p>Every rule here exists to keep the sending mailbox out of trouble:
 * <ul>
 *   <li><b>One message per recipient</b> — never CC/BCC. Recipients don't see each other's
 *       addresses, and a single bad address can't poison delivery for the whole batch.</li>
 *   <li><b>Paced sending</b> with a random jitter on top of the fixed delay, so the cadence
 *       doesn't look machine-generated.</li>
 *   <li><b>Deduplication</b> by address — the same mailbox hit twice in one run looks like
 *       a misconfigured bulk sender.</li>
 *   <li><b>Per-campaign ceiling</b>, checked up front, to stay under the provider's daily cap.</li>
 *   <li><b>Circuit breaker</b> — consecutive failures abort the run. Repeated failures mean the
 *       provider is throttling us; pushing through is what escalates a throttle into a block.</li>
 *   <li><b>Retry only what's retryable</b> — transient (4xx) errors back off and retry, permanent
 *       (5xx) ones are recorded and skipped rather than hammered.</li>
 *   <li><b>Proper headers</b> — real From display name, Reply-To, and List-Unsubscribe, plus a
 *       plain-text alternative alongside the HTML.</li>
 * </ul>
 *
 * <p>Exactly one campaign runs at a time, process-wide. That is a deliberate limit: two
 * concurrent runs would each honour the throttle while together doubling the real send rate.
 */
@Service
@Slf4j
public class EmailCampaignService {

    /** A 5xx reply is a permanent refusal — retrying it wastes quota and looks worse. */
    private static final Pattern PERMANENT_SMTP_ERROR = Pattern.compile("\\b5\\d\\d\\b");
    private static final long CANCEL_POLL_MS = 200;

    private final JavaMailSender mailSender;
    private final MailCampaignProperties props;
    private final MailTemplateService templates;
    private final CampaignLogService campaignLog;
    private final EmailFooterService footers;
    private final HtmlSanitizer sanitizer;
    private final ContactRepository contacts;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "email-campaign");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile RunState state = RunState.idle();

    public EmailCampaignService(JavaMailSender mailSender,
                                MailCampaignProperties props,
                                MailTemplateService templates,
                                CampaignLogService campaignLog,
                                EmailFooterService footers,
                                HtmlSanitizer sanitizer,
                                ContactRepository contacts) {
        this.mailSender = mailSender;
        this.props = props;
        this.templates = templates;
        this.campaignLog = campaignLog;
        this.footers = footers;
        this.sanitizer = sanitizer;
        this.contacts = contacts;
    }

    /**
     * Body + chosen footer, resolved once per campaign rather than per message. Editing or
     * deleting the footer mid-run therefore can't change what half the recipients receive.
     * Placeholders are substituted afterwards, so a footer may use them too.
     */
    private String composeBody(CampaignRequest req) {
        String body = sanitizer.clean(req.getHtmlBody());
        if (req.getFooterId() == null) {
            return body;
        }
        return body + sanitizer.clean(footers.get(req.getFooterId()).html());
    }

    @PreDestroy
    void shutdown() {
        cancelRequested.set(true);
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- public API

    /** Validate, preflight SMTP, then hand the run to the background worker. */
    public synchronized CampaignStatusResponse start(CampaignRequest req) {
        requireConfigured();
        if (running.get()) {
            throw new IllegalStateException(
                    "A campaign is already running. Wait for it to finish or cancel it first.");
        }

        List<CampaignRecipientRequest> deduped = dedupe(req.getRecipients());
        int duplicates = req.getRecipients().size() - deduped.size();

        // Last line of defence: an email list is a client-side snapshot, so an address
        // flagged not-working after it was collected would otherwise still be mailed.
        List<CampaignRecipientRequest> recipients = dropNotWorking(deduped);
        int dead = deduped.size() - recipients.size();

        if (recipients.isEmpty()) {
            throw new IllegalArgumentException(dead > 0
                    ? "No valid recipients left: every remaining address is flagged as not working."
                    : "No valid recipients left after removing duplicates and blanks.");
        }
        if (recipients.size() > props.getMaxRecipientsPerCampaign()) {
            throw new IllegalArgumentException(
                    "This campaign has %d recipients but the per-campaign limit is %d. Split it into smaller batches to stay under the mailbox's daily cap."
                            .formatted(recipients.size(), props.getMaxRecipientsPerCampaign()));
        }

        // Resolve the footer before anything is sent, so a bad footerId is a 404 up front
        // rather than a campaign that dies on its first message.
        String composedHtml = composeBody(req);

        // Fail here rather than at message 1 of 200: a bad password should not leave a
        // half-sent campaign and a log full of identical auth errors behind it.
        verifyConnection();

        cancelRequested.set(false);
        running.set(true);
        state = RunState.starting(req.getSubject(), recipients.size());

        String carriedOver = campaignLog.beginRun(req.getSubject(), recipients.size(),
                props.getMinDelayMs(), props.getMaxDelayMs());
        if (duplicates > 0) {
            campaignLog.append("NOTE   %d duplicate address(es) removed before sending".formatted(duplicates));
        }
        if (dead > 0) {
            campaignLog.append("NOTE   %d address(es) skipped: flagged as not working".formatted(dead));
        }
        log.info("Campaign started: {} recipient(s), subject '{}' ({})",
                recipients.size(), req.getSubject(), carriedOver);

        worker.submit(() -> execute(req.getSubject(), composedHtml, recipients, duplicates));
        return status();
    }

    public CampaignStatusResponse status() {
        RunState s = state;
        return new CampaignStatusResponse(
                s.state(), running.get(), s.subject(), s.total(), s.sent(), s.failed(), s.skipped(),
                s.currentEmail(), s.startedAt(), s.finishedAt(), etaSeconds(s), s.lastError(), s.message());
    }

    public String logContents() {
        return campaignLog.read();
    }

    /** Ask the running campaign to stop after the message in flight. */
    public CampaignStatusResponse cancel() {
        if (!running.get()) {
            throw new IllegalStateException("No campaign is running.");
        }
        cancelRequested.set(true);
        campaignLog.append("CANCEL requested — stopping after the current message");
        return status();
    }

    /**
     * Send a single message to one address using the real template and transport, without
     * touching campaign state or the log. The safe way to check credentials and see how the
     * circular actually renders in a client before committing to a full run.
     */
    public void sendTest(String to, CampaignRequest req) {
        requireConfigured();
        if (req.getSubject() == null || req.getSubject().isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        if (req.getHtmlBody() == null || req.getHtmlBody().isBlank()) {
            throw new IllegalArgumentException("body is required");
        }
        CampaignRecipientRequest sample = req.getRecipients() == null || req.getRecipients().isEmpty()
                ? previewRecipient(to)
                : withEmail(req.getRecipients().get(0), to);
        verifyConnection();
        // Composed the same way as a real send, footer included — that's the whole point of
        // a test: seeing exactly what a recipient will get.
        deliver(sample, "[TEST] " + req.getSubject(), composeBody(req));
        log.info("Test circular sent to {}", to);
    }

    public CampaignConfigResponse config() {
        List<String> missing = missingSettings();
        JavaMailSenderImpl impl = asImpl();
        return new CampaignConfigResponse(
                props.isEnabled(),
                props.isEnabled() && missing.isEmpty(),
                missing,
                impl == null ? null : impl.getHost(),
                impl == null ? 0 : impl.getPort(),
                impl == null ? null : impl.getUsername(),
                props.getFromAddress(),
                props.getFromName(),
                props.getReplyTo(),
                props.getMinDelayMs(),
                props.getMaxDelayMs(),
                props.getMaxRecipientsPerCampaign(),
                isSet(props.getUnsubscribeMailto()));
    }

    // ---------------------------------------------------------------- the run

    private void execute(String subject, String composedHtml,
                         List<CampaignRecipientRequest> recipients, int duplicates) {
        int sent = 0;
        int failed = 0;
        int skipped = duplicates;
        int consecutiveFailures = 0;
        String finalState = "COMPLETED";
        String finalMessage = null;

        try {
            for (int i = 0; i < recipients.size(); i++) {
                if (cancelRequested.get()) {
                    finalState = "CANCELLED";
                    finalMessage = "Cancelled after %d of %d message(s).".formatted(sent, recipients.size());
                    campaignLog.append("CANCELLED by user");
                    break;
                }

                // Pace before every message except the first, so the run starts immediately
                // but no two messages ever leave closer together than the configured gap.
                if (i > 0 && !sleepRandomDelay()) {
                    finalState = "CANCELLED";
                    finalMessage = "Cancelled after %d of %d message(s).".formatted(sent, recipients.size());
                    campaignLog.append("CANCELLED by user");
                    break;
                }

                CampaignRecipientRequest r = recipients.get(i);
                state = state.progress(r.getEmail(), sent, failed, skipped);

                try {
                    sendWithRetries(r, subject, composedHtml);
                    sent++;
                    consecutiveFailures = 0;
                    campaignLog.append("SENT   %-40s %s".formatted(r.getEmail(), describe(r)));
                } catch (MailAuthenticationException e) {
                    // Credentials rejected mid-run: every remaining message would fail the same
                    // way, and repeated auth failures are themselves a lockout trigger.
                    failed++;
                    finalState = "ABORTED";
                    finalMessage = "SMTP authentication failed — campaign aborted. Check MAIL_USERNAME / MAIL_PASSWORD.";
                    state = state.withError(rootMessage(e));
                    campaignLog.append("ABORT  authentication rejected: " + rootMessage(e));
                    break;
                } catch (Exception e) {
                    failed++;
                    consecutiveFailures++;
                    state = state.withError(rootMessage(e));
                    campaignLog.append("FAILED %-40s %s".formatted(r.getEmail(), rootMessage(e)));

                    if (consecutiveFailures >= props.getAbortAfterConsecutiveFailures()) {
                        finalState = "ABORTED";
                        finalMessage = "Aborted after %d consecutive failures — the mail server is refusing messages."
                                .formatted(consecutiveFailures);
                        campaignLog.append("ABORT  %d consecutive failures".formatted(consecutiveFailures));
                        break;
                    }
                }
                state = state.progress(r.getEmail(), sent, failed, skipped);
            }

            if ("COMPLETED".equals(finalState) && failed > 0) {
                finalState = "COMPLETED_WITH_ERRORS";
                finalMessage = "%d sent, %d failed. The log is kept for the next run.".formatted(sent, failed);
            } else if ("COMPLETED".equals(finalState)) {
                finalMessage = "All %d message(s) sent.".formatted(sent);
            }
        } catch (Throwable t) {
            finalState = "ABORTED";
            finalMessage = "Campaign stopped unexpectedly: " + rootMessage(t);
            campaignLog.append("ABORT  unexpected error: " + rootMessage(t));
            log.error("Campaign failed unexpectedly", t);
        } finally {
            campaignLog.endRun(finalState, sent, failed, skipped);
            state = state.finished(finalState, sent, failed, skipped, finalMessage);
            running.set(false);
            cancelRequested.set(false);
            log.info("Campaign finished: {} — sent={} failed={} skipped={}", finalState, sent, failed, skipped);
        }
    }

    private void sendWithRetries(CampaignRecipientRequest r, String subject, String composedHtml) {
        int attempt = 0;
        long backoff = props.getRetryBackoffMs();
        while (true) {
            try {
                deliver(r, subject, composedHtml);
                return;
            } catch (MailAuthenticationException e) {
                throw e; // never retried — handled by the caller as a hard abort
            } catch (MailException e) {
                boolean permanent = PERMANENT_SMTP_ERROR.matcher(String.valueOf(rootMessage(e))).find();
                if (permanent || attempt >= props.getMaxRetries()) {
                    throw e;
                }
                attempt++;
                campaignLog.append("RETRY  %-40s attempt %d after %s (%s)"
                        .formatted(r.getEmail(), attempt, formatDelay(backoff), rootMessage(e)));
                if (!sleepInterruptible(backoff)) {
                    throw e;
                }
                backoff *= 2;
            }
        }
    }

    /** Build and hand one personalised message to the transport. */
    private void deliver(CampaignRecipientRequest r, String subject, String htmlTemplate) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            mime.setFrom(new InternetAddress(props.getFromAddress(), props.getFromName(), "UTF-8"));
            mime.setRecipient(Message.RecipientType.TO, new InternetAddress(r.getEmail().trim()));
            mime.setSubject(templates.renderText(subject, r), "UTF-8");
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
            // carrying no attachment is both wasteful and a mild spam signal. Text part first —
            // alternative orders parts least- to most-preferred.
            MimeMultipart alternative = new MimeMultipart("alternative");
            alternative.addBodyPart(textPart);
            alternative.addBodyPart(htmlPart);
            mime.setContent(alternative);

            if (isSet(props.getUnsubscribeMailto())) {
                mime.setHeader("List-Unsubscribe", "<mailto:%s>".formatted(props.getUnsubscribeMailto().trim()));
            }
            mailSender.send(mime);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("Could not build the message for " + r.getEmail() + ": " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Keep the first occurrence of each address, case-insensitively. */
    /**
     * Drop recipients whose address is flagged not working. Matched on the address itself
     * rather than contactId, so a hand-typed or edited row in the email list is caught too.
     */
    private List<CampaignRecipientRequest> dropNotWorking(List<CampaignRecipientRequest> input) {
        Set<String> dead = contacts.findNotWorkingEmailValues();
        if (dead.isEmpty()) return input;
        return input.stream()
                .filter(r -> !dead.contains(r.getEmail().trim().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private List<CampaignRecipientRequest> dedupe(List<CampaignRecipientRequest> input) {
        Set<String> seen = new LinkedHashSet<>();
        List<CampaignRecipientRequest> out = new ArrayList<>();
        for (CampaignRecipientRequest r : input) {
            if (r == null || r.getEmail() == null || r.getEmail().isBlank()) {
                continue;
            }
            if (seen.add(r.getEmail().trim().toLowerCase(Locale.ROOT))) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Wait a random interval drawn from [minDelayMs, maxDelayMs] before the next message.
     *
     * @return false if a cancel arrived while waiting
     */
    private boolean sleepRandomDelay() {
        return sleepInterruptible(randomDelayMs());
    }

    private long randomDelayMs() {
        long min = Math.max(0, props.getMinDelayMs());
        long max = Math.max(min, props.getMaxDelayMs());
        // nextLong's bound is exclusive, so +1 keeps maxDelayMs itself reachable.
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /** Mean gap, used for the estimate shown to the user. */
    private long averageDelayMs() {
        long min = Math.max(0, props.getMinDelayMs());
        long max = Math.max(min, props.getMaxDelayMs());
        return (min + max) / 2;
    }

    /** Sleep in slices so a cancel is picked up promptly instead of after the full delay. */
    private boolean sleepInterruptible(long totalMs) {
        long remaining = totalMs;
        while (remaining > 0) {
            if (cancelRequested.get()) {
                return false;
            }
            long slice = Math.min(CANCEL_POLL_MS, remaining);
            try {
                TimeUnit.MILLISECONDS.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= slice;
        }
        return !cancelRequested.get();
    }

    private void requireConfigured() {
        if (!props.isEnabled()) {
            throw new MailNotConfiguredException(
                    "Sending is disabled. Set MAIL_ENABLED=true once the SMTP credentials are in place.");
        }
        List<String> missing = missingSettings();
        if (!missing.isEmpty()) {
            throw new MailNotConfiguredException("Mail is not fully configured — missing: " + String.join(", ", missing));
        }
    }

    private List<String> missingSettings() {
        List<String> missing = new ArrayList<>();
        JavaMailSenderImpl impl = asImpl();
        if (impl == null || !isSet(impl.getHost())) {
            missing.add("MAIL_HOST");
        }
        if (impl == null || !isSet(impl.getUsername())) {
            missing.add("MAIL_USERNAME");
        }
        if (impl == null || !isSet(impl.getPassword())) {
            missing.add("MAIL_PASSWORD");
        }
        if (!isSet(props.getFromAddress())) {
            missing.add("MAIL_FROM");
        }
        return missing;
    }

    private void verifyConnection() {
        JavaMailSenderImpl impl = asImpl();
        if (impl == null) {
            return;
        }
        try {
            impl.testConnection();
        } catch (Exception e) {
            throw new MailNotConfiguredException(
                    "Could not connect to %s:%d — %s".formatted(impl.getHost(), impl.getPort(), rootMessage(e)));
        }
    }

    private JavaMailSenderImpl asImpl() {
        return mailSender instanceof JavaMailSenderImpl impl ? impl : null;
    }

    private Long etaSeconds(RunState s) {
        if (!running.get() || s.total() <= 0) {
            return null;
        }
        int done = s.sent() + s.failed();
        int remaining = Math.max(0, s.total() - done);
        return remaining * averageDelayMs() / 1000;
    }

    private static CampaignRecipientRequest withEmail(CampaignRecipientRequest src, String email) {
        return new CampaignRecipientRequest(email, src.getContactId(), src.getGreetingName(),
                src.getPersonName(), src.getTitle(), src.getCompanyName());
    }

    private static CampaignRecipientRequest previewRecipient(String email) {
        return new CampaignRecipientRequest(email, null, "Sirs", "Sample Recipient", "Mr", "Sample Company Ltd");
    }

    private static String describe(CampaignRecipientRequest r) {
        String who = r.getPersonName() != null && !r.getPersonName().isBlank() ? r.getPersonName() : "-";
        String co = r.getCompanyName() != null && !r.getCompanyName().isBlank() ? r.getCompanyName() : "-";
        return "%s / %s".formatted(who, co);
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            return cur.getClass().getSimpleName();
        }
        String clean = msg.replaceAll("\\s+", " ").trim();
        // Some exceptions carry a bare token as their message — UnknownHostException's is just
        // the hostname, which reads as nonsense in a log. Qualify those with the exception type.
        return clean.contains(" ") ? clean : cur.getClass().getSimpleName() + ": " + clean;
    }

    /** Sub-second delays must not render as "0s" in the log. */
    private static String formatDelay(long ms) {
        return ms < 1000 ? ms + "ms" : (ms / 1000) + "s";
    }

    /** Immutable snapshot of progress — swapped wholesale so readers never see a torn state. */
    private record RunState(String state, String subject, int total, int sent, int failed, int skipped,
                            String currentEmail, LocalDateTime startedAt, LocalDateTime finishedAt,
                            String lastError, String message) {

        static RunState idle() {
            return new RunState("IDLE", null, 0, 0, 0, 0, null, null, null, null, null);
        }

        static RunState starting(String subject, int total) {
            return new RunState("RUNNING", subject, total, 0, 0, 0, null, LocalDateTime.now(), null, null, null);
        }

        RunState progress(String email, int sent, int failed, int skipped) {
            return new RunState(state, subject, total, sent, failed, skipped, email, startedAt, null, lastError, message);
        }

        RunState withError(String error) {
            return new RunState(state, subject, total, sent, failed, skipped, currentEmail, startedAt, null, error, message);
        }

        RunState finished(String finalState, int sent, int failed, int skipped, String finalMessage) {
            return new RunState(finalState, subject, total, sent, failed, skipped, null,
                    startedAt, LocalDateTime.now(), lastError, finalMessage);
        }
    }
}
