package com.chartering.service;

import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.CampaignConfigResponse;
import com.chartering.dto.CampaignRecipientRequest;
import com.chartering.dto.CampaignRequest;
import com.chartering.dto.CampaignStatusResponse;
import com.chartering.dto.CirculationRunResponse;
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
import java.util.Properties;
import java.time.Duration;
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
 *   <li><b>Per-run ceiling</b>, with the campaign split into as many runs as it takes and a
 *       quiet gap between them. A list longer than the ceiling is not refused — refusing it
 *       only moved the work to the user, who would then send the same volume by hand, in
 *       whatever bursts they felt like. The ceiling caps the burst, not the circular.</li>
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
 *
 * <h2>Stopping and starting again</h2>
 * <p>A run can be paused and picked up later, and a finished one can be sent again. Neither
 * needs anything held in memory: the circulation history already records the circular as it
 * was composed and every address with its own status, so the queue for a resume is simply
 * "the rows still marked PENDING". That is what makes a resume survive an API restart -
 * there is no in-flight state to lose, only rows to read back.
 *
 * <ul>
 *   <li><b>Pause</b> stops after the message in hand and leaves the run open. Cancel stops
 *       the same way but closes it.</li>
 *   <li><b>Resume</b> continues the <em>same</em> run, mailing only its PENDING rows, so one
 *       circular sent over two sittings stays one entry in history.</li>
 *   <li><b>Restart</b> opens a <em>new</em> run over the same circular and addresses. The
 *       first send happened; rewriting its record afterwards would make history useless.</li>
 *   <li>An API restart mid-send is treated as a pause, not a failure - see
 *       {@code CirculationHistoryService.closeInterruptedRuns}.</li>
 * </ul>
 */
@Service
@Slf4j
public class EmailCampaignService {

    /** A 5xx reply is a permanent refusal — retrying it wastes quota and looks worse. */
    private static final Pattern PERMANENT_SMTP_ERROR = Pattern.compile("\\b5\\d\\d\\b");
    private static final long CANCEL_POLL_MS = 200;
    /** How long a shutdown waits for the worker to record the pause before killing it. */
    private static final long SHUTDOWN_GRACE_MS = 10_000;

    private final JavaMailSender mailSender;
    private final MailCampaignProperties props;
    private final MailTemplateService templates;
    private final CampaignLogService campaignLog;
    private final EmailFooterService footers;
    private final HtmlSanitizer sanitizer;
    private final ContactRepository contacts;
    private final CirculationHistoryService history;
    private final CirculationListService circulationLists;
    private final SettingsService settings;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "email-campaign");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * How the run in flight has been asked to stop. Pause and cancel are the same
     * mechanism — stop after the message in hand — and differ only in what is written
     * afterwards, so they share one flag rather than racing two.
     */
    private volatile Stop stopRequested = Stop.NONE;
    private volatile RunState state = RunState.idle();

    /** Nothing asked for; stop and keep the run resumable; stop and close the run. */
    private enum Stop {NONE, PAUSE, CANCEL}

    /**
     * The settings the running campaign started with. Kept so the progress endpoint's ETA
     * uses the pacing the run is actually honouring rather than whatever Settings says now.
     */
    private volatile SettingsService.CirculationSettings activeSettings;

    public EmailCampaignService(JavaMailSender mailSender,
                                MailCampaignProperties props,
                                MailTemplateService templates,
                                CampaignLogService campaignLog,
                                EmailFooterService footers,
                                HtmlSanitizer sanitizer,
                                ContactRepository contacts,
                                CirculationHistoryService history,
                                CirculationListService circulationLists,
                                SettingsService settings) {
        this.mailSender = mailSender;
        this.props = props;
        this.templates = templates;
        this.campaignLog = campaignLog;
        this.footers = footers;
        this.sanitizer = sanitizer;
        this.contacts = contacts;
        this.history = history;
        this.circulationLists = circulationLists;
        this.settings = settings;
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

    /**
     * On the way down, ask the run to pause rather than cancel, and give it a moment to
     * write that down. A deployment restart is not a decision to abandon a circular — the
     * run stops where it is and can be picked up afterwards. If the write does not make it
     * out in time, {@code CirculationHistoryService.closeInterruptedRuns} catches the run
     * at the next startup and leaves it resumable anyway.
     */
    @PreDestroy
    void shutdown() {
        stopRequested = Stop.PAUSE;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- public API

    /** Validate, preflight SMTP, then hand the run to the background worker. */
    public synchronized CampaignStatusResponse start(CampaignRequest req) {
        // Resolve the footer before anything is sent, so a bad footerId is a 404 up front
        // rather than a campaign that dies on its first message.
        String composedHtml = composeBody(req);
        String footerName = req.getFooterId() == null ? null : footers.get(req.getFooterId()).name();
        return launch(req.getSubject(), composedHtml, req.getFooterId(), footerName,
                req.getListId(), listName(req.getListId()), req.getRecipients(), "started");
    }

    /**
     * Send a past circulation again, from the top, as a run of its own.
     *
     * <p>A new run rather than a rewrite of the old one: the first send happened, and
     * history saying otherwise afterwards would make it useless. The circular is replayed
     * from what that run stored — footer included, already composed — so the re-send is the
     * same message even if the footer has been edited or deleted since.
     *
     * <p>The address list goes back through the normal filters, so anyone flagged
     * not-working in the meantime is dropped here as they would be on a fresh send.
     */
    public synchronized CampaignStatusResponse restart(Long runId) {
        CirculationHistoryService.RestartableRun src = history.loadForRestart(runId);
        if (src.recipients().isEmpty()) {
            throw new IllegalArgumentException(
                    "That circulation has no addresses to send to - nothing to restart.");
        }
        return launch(src.subject(), src.composedHtml(), src.footerId(), src.footerName(),
                src.listId(), src.listName(), src.recipients(), "restarted");
    }

    /**
     * Shared path for every way a campaign begins: filter, preflight, open the record, hand
     * the run to the worker. The body arrives already composed because a restart replays a
     * stored circular rather than recomposing one.
     */
    private synchronized CampaignStatusResponse launch(String subject, String composedHtml,
                                                       Long footerId, String footerName,
                                                       Long listId, String listName,
                                                       List<CampaignRecipientRequest> requested,
                                                       String verb) {
        requireConfigured();
        requireNotRunning();

        Split byDuplicate = dedupe(requested);
        List<CampaignRecipientRequest> deduped = byDuplicate.kept();
        int duplicates = byDuplicate.dropped().size();

        // Last line of defence: a list is a snapshot taken when the addresses were
        // collected, so one flagged not-working since would otherwise still be mailed.
        Split byWorking = dropNotWorking(deduped);
        List<CampaignRecipientRequest> recipients = byWorking.kept();
        int dead = byWorking.dropped().size();

        if (recipients.isEmpty()) {
            throw new IllegalArgumentException(dead > 0
                    ? "No valid recipients left: every remaining address is flagged as not working."
                    : "No valid recipients left after removing duplicates and blanks.");
        }
        // Resolved once, here: the pacing and cap a run starts with are the ones it keeps,
        // so changing them in Settings mid-send cannot speed up half a campaign.
        SettingsService.CirculationSettings cfg = settings.circulation();

        // Over the per-run ceiling the campaign is split rather than refused: the run size
        // is what the provider's per-hour allowance cares about, and sending 6 x 50 an hour
        // apart is the same circular sent safely instead of a dialog telling the user to do
        // it themselves. What it cannot do is fit a campaign inside a *daily* cap that is
        // smaller than the campaign - that is the user's call, made before they press Send.
        int batchCount = cfg.batchCount(recipients.size());

        // Fail here rather than at message 1 of 200: a bad password should not leave a
        // half-sent campaign and a log full of identical auth errors behind it.
        JavaMailSenderImpl sender = senderFor(cfg);
        verifyConnection(sender);

        String carriedOver = campaignLog.beginRun(subject, recipients.size(),
                cfg.minDelayMs(), cfg.maxDelayMs());
        if (batchCount > 1) {
            campaignLog.append("PLAN   %d recipient(s) over %d run(s) of up to %d, %s apart"
                    .formatted(recipients.size(), batchCount, cfg.maxRecipientsPerCampaign(),
                            formatDelay(cfg.batchPauseMs())));
        }
        if (duplicates > 0) {
            campaignLog.append("NOTE   %d duplicate address(es) removed before sending".formatted(duplicates));
        }
        if (dead > 0) {
            campaignLog.append("NOTE   %d address(es) skipped: flagged as not working".formatted(dead));
        }

        // The permanent record, opened before the first message so a run that dies halfway
        // still leaves a history entry naming everyone it had already reached. A failure to
        // record must not stop the send - the text log is still written either way.
        CirculationHistoryService.StartedRun run;
        try {
            run = history.begin(subject, composedHtml, footerId, footerName, listId, listName,
                    recipients, byDuplicate.dropped(), byWorking.dropped());
        } catch (RuntimeException e) {
            run = CirculationHistoryService.StartedRun.none();
            campaignLog.append("NOTE   history could not be opened for this run: " + rootMessage(e));
            log.warn("Could not open the circulation history record", e);
        }

        log.info("Campaign {}: {} recipient(s) over {} run(s), subject '{}' ({})",
                verb, recipients.size(), batchCount, subject, carriedOver);

        return submit(new Job(run, subject, composedHtml, recipients, cfg, sender,
                0, 0, duplicates + dead, recipients.size()));
    }

    /**
     * Carry a stopped run on from where it stopped - paused by hand, cancelled, aborted on
     * errors, or cut off by an API restart. Only the addresses still marked PENDING are
     * mailed, so nobody hears from us twice.
     *
     * <p>The same run row is continued rather than a new one opened: one circular sent in
     * two sittings is one circulation, and splitting it across two history entries would
     * make "who received this?" a question with two answers.
     */
    public synchronized CampaignStatusResponse resume(Long runId) {
        requireConfigured();
        requireNotRunning();

        CirculationHistoryService.ResumableRun src = history.loadForResume(runId);
        if (src.pending().isEmpty()) {
            throw new IllegalArgumentException(
                    "That circulation has nobody left to send to - there is nothing to resume.");
        }

        // Re-checked rather than trusted: the gap before a resume is exactly when an address
        // gets flagged dead, and the whole point of the flag is that it is honoured late.
        Set<String> dead = contacts.findNotWorkingEmailValues();
        List<CirculationHistoryService.PendingRecipient> live = new ArrayList<>();
        List<Long> dropped = new ArrayList<>();
        for (CirculationHistoryService.PendingRecipient pending : src.pending()) {
            if (dead.contains(pending.fields().getEmail().trim().toLowerCase(Locale.ROOT))) {
                dropped.add(pending.recipientId());
            } else {
                live.add(pending);
            }
        }
        if (live.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nobody is left to resume: every remaining address is flagged as not working.");
        }

        SettingsService.CirculationSettings cfg = settings.circulation();
        JavaMailSenderImpl sender = senderFor(cfg);
        verifyConnection(sender);

        int skipped = src.alreadySkipped() + dropped.size();
        // The run's total loses whoever will now never be mailed, so sent + failed can still
        // reach it and the progress bar still ends where it should.
        int total = src.alreadySent() + src.alreadyFailed() + live.size();
        if (!dropped.isEmpty()) {
            history.markNotWorking(dropped);
        }
        history.reopen(runId, total, skipped);

        int batchCount = cfg.batchCount(live.size());
        String carriedOver = campaignLog.beginRun(src.subject(), live.size(),
                cfg.minDelayMs(), cfg.maxDelayMs());
        campaignLog.append("RESUME %d of %d already done - %d recipient(s) left"
                .formatted(src.alreadySent() + src.alreadyFailed(), total, live.size()));
        if (batchCount > 1) {
            campaignLog.append("PLAN   %d recipient(s) over %d run(s) of up to %d, %s apart"
                    .formatted(live.size(), batchCount, cfg.maxRecipientsPerCampaign(),
                            formatDelay(cfg.batchPauseMs())));
        }
        if (!dropped.isEmpty()) {
            campaignLog.append("NOTE   %d address(es) skipped: flagged as not working since the run started"
                    .formatted(dropped.size()));
        }

        CirculationHistoryService.StartedRun record = new CirculationHistoryService.StartedRun(
                runId, live.stream().map(CirculationHistoryService.PendingRecipient::recipientId).toList());
        List<CampaignRecipientRequest> recipients = live.stream()
                .map(CirculationHistoryService.PendingRecipient::fields)
                .toList();

        log.info("Campaign resumed: run {}, {} recipient(s) left of {} ({})",
                runId, live.size(), total, carriedOver);

        return submit(new Job(record, src.subject(), src.composedHtml(), recipients, cfg, sender,
                src.alreadySent(), src.alreadyFailed(), skipped, total));
    }

    /** Flip the in-memory state over to the new job and hand it to the worker. */
    private CampaignStatusResponse submit(Job job) {
        activeSettings = job.cfg();
        stopRequested = Stop.NONE;
        running.set(true);
        state = RunState.starting(job);
        worker.submit(() -> execute(job));
        return status();
    }

    private void requireNotRunning() {
        if (running.get()) {
            throw new IllegalStateException(
                    "A campaign is already running. Wait for it to finish, or pause or cancel it first.");
        }
    }

    /** History stores the list by name, so deleting the list later cannot rewrite the run. */
    private String listName(Long listId) {
        if (listId == null) {
            return null;
        }
        try {
            return circulationLists.get(listId).name();
        } catch (RuntimeException e) {
            return null; // a list deleted between compose and send is not worth failing over
        }
    }

    public CampaignStatusResponse status() {
        RunState s = state;
        return new CampaignStatusResponse(
                s.state(), running.get(), s.runId(), s.subject(), s.total(), s.sent(), s.failed(), s.skipped(),
                s.currentEmail(), s.startedAt(), s.finishedAt(), etaSeconds(s), s.lastError(), s.message(),
                s.batch(), s.batchCount(), s.paused(), s.nextBatchAt(),
                // Resumable only once the worker has actually stopped: while it is still
                // winding down, its recipient rows are not settled and a resume would race it.
                !running.get() && s.runId() != null && s.sent() + s.failed() < s.total());
    }

    /** Every circulation with somebody still to reach, newest first. */
    public List<CirculationRunResponse> resumable() {
        return history.resumable();
    }

    public String logContents() {
        return campaignLog.read();
    }

    /**
     * Ask the running campaign to stop after the message in flight and close the run.
     *
     * <p>Cancelling leaves the untouched addresses PENDING, exactly as a pause does, so a
     * cancel decided too hastily is still recoverable — the difference is what the run says
     * about itself afterwards, not what it destroys.
     */
    public CampaignStatusResponse cancel() {
        return stop(Stop.CANCEL, "CANCEL requested - stopping after the current message");
    }

    /**
     * Ask the running campaign to stop after the message in flight and stay open.
     *
     * <p>Stopping cannot be immediate: a message already handed to the transport has been
     * sent whatever we do here, and pretending otherwise would leave history claiming
     * somebody was never reached who has the circular in their inbox.
     */
    public CampaignStatusResponse pause() {
        return stop(Stop.PAUSE, "PAUSE  requested - stopping after the current message");
    }

    private synchronized CampaignStatusResponse stop(Stop mode, String logLine) {
        if (!running.get()) {
            throw new IllegalStateException("No campaign is running.");
        }
        // A cancel after a pause still cancels; a pause after a cancel does not un-cancel,
        // because the user has already been told the run was being closed.
        if (stopRequested != Stop.CANCEL) {
            stopRequested = mode;
            campaignLog.append(logLine);
        }
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
        SettingsService.CirculationSettings cfg = settings.circulation();
        JavaMailSenderImpl sender = senderFor(cfg);
        verifyConnection(sender);
        // Composed the same way as a real send, footer included — that's the whole point of
        // a test: seeing exactly what a recipient will get.
        deliver(sample, "[TEST] " + req.getSubject(), composeBody(req), sender, cfg);
        log.info("Test circular sent to {}", to);
    }

    public CampaignConfigResponse config() {
        List<String> missing = missingSettings();
        JavaMailSenderImpl impl = asImpl();
        // Host, pacing and cap come from Settings; the credentials and identity still come
        // from the environment, which is why they are read off different objects here.
        SettingsService.CirculationSettings cfg = settings.circulation();
        return new CampaignConfigResponse(
                props.isEnabled(),
                props.isEnabled() && missing.isEmpty(),
                missing,
                cfg.smtpHost(),
                cfg.smtpPort(),
                impl == null ? null : impl.getUsername(),
                cfg.fromAddress(),
                cfg.fromName(),
                props.getReplyTo(),
                cfg.minDelayMs(),
                cfg.maxDelayMs(),
                cfg.maxRecipientsPerCampaign(),
                cfg.batchPauseMs(),
                isSet(props.getUnsubscribeMailto()));
    }

    // ---------------------------------------------------------------- the run

    /**
     * One sitting of a campaign: every remaining recipient, paced, until the list runs out
     * or something stops it.
     *
     * <p>The counters start from what the run had already achieved rather than from zero,
     * because a resumed run is the same circulation carrying on - "sent 137 of 256" has to
     * keep meaning the same thing across a pause.
     */
    private void execute(Job job) {
        CirculationHistoryService.StartedRun record = job.record();
        SettingsService.CirculationSettings cfg = job.cfg();
        List<CampaignRecipientRequest> recipients = job.recipients();

        int sent = job.alreadySent();
        int failed = job.alreadyFailed();
        int skipped = job.skipped();
        int consecutiveFailures = 0;
        String finalState = "COMPLETED";
        String finalMessage = null;
        int batchSize = Math.max(1, cfg.maxRecipientsPerCampaign());
        int batchCount = cfg.batchCount(recipients.size());
        int done = 0;

        try {
            for (int i = 0; i < recipients.size(); i++) {
                Stop stop = stopRequested;
                if (stop != Stop.NONE) {
                    finalState = stoppedState(stop);
                    finalMessage = stoppedMessage(stop, sent, job.total());
                    campaignLog.append(stoppedLogLine(stop));
                    break;
                }

                // A run boundary takes the long pause in place of the short one - the two are
                // the same mechanism at different scales, and stacking them would only make
                // the gap the user configured wrong by a few seconds.
                boolean startsNewBatch = i > 0 && i % batchSize == 0;
                if (startsNewBatch) {
                    int next = i / batchSize + 1;
                    int size = Math.min(batchSize, recipients.size() - i);
                    campaignLog.append("PAUSE  run %d of %d done - next %d recipient(s) in %s"
                            .formatted(next - 1, batchCount, size, formatDelay(cfg.batchPauseMs())));
                    state = state.pausing(next, LocalDateTime.now()
                            .plusNanos(TimeUnit.MILLISECONDS.toNanos(cfg.batchPauseMs())));
                    stop = sleepInterruptible(cfg.batchPauseMs());
                    if (stop != Stop.NONE) {
                        finalState = stoppedState(stop);
                        finalMessage = stoppedMessage(stop, sent, job.total());
                        campaignLog.append(stoppedLogLine(stop));
                        break;
                    }
                    state = state.resumed();
                    campaignLog.append("RUN    %d of %d - %d recipient(s)".formatted(next, batchCount, size));
                } else if (i > 0 && (stop = sleepRandomDelay(cfg)) != Stop.NONE) {
                    // Pace before every message except the first, so the run starts immediately
                    // but no two messages ever leave closer together than the configured gap.
                    finalState = stoppedState(stop);
                    finalMessage = stoppedMessage(stop, sent, job.total());
                    campaignLog.append(stoppedLogLine(stop));
                    break;
                }

                CampaignRecipientRequest r = recipients.get(i);
                state = state.progress(r.getEmail(), sent, failed, skipped);

                Attempted outcome = new Attempted();
                try {
                    sendWithRetries(r, job.subject(), job.composedHtml(), outcome, job.sender(), cfg);
                    sent++;
                    consecutiveFailures = 0;
                    campaignLog.append("SENT   %-40s %s".formatted(r.getEmail(), describe(r)));
                    recordSent(record, i, outcome.attempts);
                } catch (MailAuthenticationException e) {
                    // Credentials rejected mid-run: every remaining message would fail the same
                    // way, and repeated auth failures are themselves a lockout trigger.
                    failed++;
                    finalState = "ABORTED";
                    finalMessage = "SMTP authentication failed - campaign aborted. Check MAIL_USERNAME / MAIL_PASSWORD.";
                    state = state.withError(rootMessage(e));
                    campaignLog.append("ABORT  authentication rejected: " + rootMessage(e));
                    recordFailed(record, i, outcome.attempts, rootMessage(e));
                    break;
                } catch (Exception e) {
                    failed++;
                    consecutiveFailures++;
                    state = state.withError(rootMessage(e));
                    campaignLog.append("FAILED %-40s %s".formatted(r.getEmail(), rootMessage(e)));
                    recordFailed(record, i, outcome.attempts, rootMessage(e));

                    if (consecutiveFailures >= props.getAbortAfterConsecutiveFailures()) {
                        finalState = "ABORTED";
                        finalMessage = "Aborted after %d consecutive failures - the mail server is refusing messages."
                                .formatted(consecutiveFailures);
                        campaignLog.append("ABORT  %d consecutive failures".formatted(consecutiveFailures));
                        break;
                    }
                }
                done++;
                state = state.progress(r.getEmail(), sent, failed, skipped);
            }

            if ("COMPLETED".equals(finalState) && failed > 0) {
                finalState = "COMPLETED_WITH_ERRORS";
                finalMessage = "%d sent, %d failed. The log is kept for the next run.".formatted(sent, failed);
            } else if ("COMPLETED".equals(finalState)) {
                finalMessage = batchCount > 1
                        ? "All %d message(s) sent, over %d runs.".formatted(sent, batchCount)
                        : "All %d message(s) sent.".formatted(sent);
            }
        } catch (Throwable t) {
            finalState = "ABORTED";
            finalMessage = "Campaign stopped unexpectedly: " + rootMessage(t);
            campaignLog.append("ABORT  unexpected error: " + rootMessage(t));
            log.error("Campaign failed unexpectedly", t);
        } finally {
            campaignLog.endRun(finalState, sent, failed, skipped);
            state = state.finished(finalState, sent, failed, skipped, finalMessage);
            // Closed inside the finally so an unexpected throw still leaves the run with an
            // outcome instead of a history entry stuck on RUNNING for ever. A pause is the one
            // outcome that leaves the run open: it is exactly what makes it resumable later.
            if (record.recording()) {
                try {
                    if (CirculationHistoryService.PAUSED.equals(finalState)) {
                        history.pause(record.runId(), sent, failed, skipped, finalMessage);
                    } else {
                        history.finish(record.runId(), finalState, sent, failed, skipped,
                                finalMessage, state.lastError());
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not close circulation run {}", record.runId(), e);
                }
            }
            running.set(false);
            stopRequested = Stop.NONE;
            log.info("Campaign finished: {} - {} message(s) attempted this sitting, sent={} failed={} skipped={}",
                    finalState, done, sent, failed, skipped);
        }
    }

    private static String stoppedState(Stop stop) {
        return stop == Stop.PAUSE ? CirculationHistoryService.PAUSED : "CANCELLED";
    }

    private static String stoppedLogLine(Stop stop) {
        return stop == Stop.PAUSE ? "PAUSED by user" : "CANCELLED by user";
    }

    /** Recording history must never take a send down with it — a lost row is not a lost email. */
    private void recordSent(CirculationHistoryService.StartedRun record, int index, int attempts) {
        if (!record.recording() || index >= record.recipientIds().size()) return;
        try {
            history.recordSent(record.recipientIds().get(index), attempts);
        } catch (RuntimeException e) {
            log.warn("Could not record a sent recipient in circulation history", e);
        }
    }

    private void recordFailed(CirculationHistoryService.StartedRun record, int index,
                              int attempts, String error) {
        if (!record.recording() || index >= record.recipientIds().size()) return;
        try {
            history.recordFailed(record.recipientIds().get(index), attempts, error);
        } catch (RuntimeException e) {
            log.warn("Could not record a failed recipient in circulation history", e);
        }
    }

    private void sendWithRetries(CampaignRecipientRequest r, String subject, String composedHtml,
                                 Attempted outcome, JavaMailSenderImpl sender,
                                 SettingsService.CirculationSettings cfg) {
        int attempt = 0;
        long backoff = props.getRetryBackoffMs();
        while (true) {
            try {
                outcome.attempts++;
                deliver(r, subject, composedHtml, sender, cfg);
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
                if (sleepInterruptible(backoff) != Stop.NONE) {
                    throw e;
                }
                backoff *= 2;
            }
        }
    }

    /** Build and hand one personalised message to the transport. */
    private void deliver(CampaignRecipientRequest r, String subject, String htmlTemplate,
                         JavaMailSenderImpl sender, SettingsService.CirculationSettings cfg) {
        MimeMessage mime = sender.createMimeMessage();
        try {
            mime.setFrom(new InternetAddress(cfg.fromAddress(), cfg.fromName(), "UTF-8"));
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
            sender.send(mime);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("Could not build the message for " + r.getEmail() + ": " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Drop recipients whose address is flagged not working. Matched on the address itself
     * rather than contactId, so a hand-typed or edited row in a list is caught too.
     *
     * <p>The dropped ones are handed back rather than discarded: history records who was
     * skipped and why, which is the question you ask when a circular has to be re-sent.
     */
    private Split dropNotWorking(List<CampaignRecipientRequest> input) {
        Set<String> dead = contacts.findNotWorkingEmailValues();
        if (dead.isEmpty()) return new Split(input, List.of());
        List<CampaignRecipientRequest> kept = new ArrayList<>();
        List<CampaignRecipientRequest> dropped = new ArrayList<>();
        for (CampaignRecipientRequest r : input) {
            (dead.contains(r.getEmail().trim().toLowerCase(Locale.ROOT)) ? dropped : kept).add(r);
        }
        return new Split(kept, dropped);
    }

    /** Keep the first occurrence of each address, case-insensitively. */
    private Split dedupe(List<CampaignRecipientRequest> input) {
        Set<String> seen = new LinkedHashSet<>();
        List<CampaignRecipientRequest> kept = new ArrayList<>();
        List<CampaignRecipientRequest> dropped = new ArrayList<>();
        for (CampaignRecipientRequest r : input) {
            if (r == null || r.getEmail() == null || r.getEmail().isBlank()) {
                continue;
            }
            (seen.add(r.getEmail().trim().toLowerCase(Locale.ROOT)) ? kept : dropped).add(r);
        }
        return new Split(kept, dropped);
    }

    /** Recipients that survived a filter, and the ones it removed. */
    private record Split(List<CampaignRecipientRequest> kept, List<CampaignRecipientRequest> dropped) {
    }

    /**
     * One sitting of work handed to the worker thread: what to send, who is left, and what
     * the run had already achieved before this sitting began.
     *
     * <p>A fresh campaign and a resumed one differ only in those last three numbers, which
     * is why both go down the same path. {@code recipients} is only who is left to reach;
     * {@code total} is the whole campaign, so progress reads the same across a pause.
     */
    private record Job(CirculationHistoryService.StartedRun record,
                       String subject, String composedHtml,
                       List<CampaignRecipientRequest> recipients,
                       SettingsService.CirculationSettings cfg, JavaMailSenderImpl sender,
                       int alreadySent, int alreadyFailed, int skipped, int total) {
    }

    /**
     * Mutable attempt counter for one address. Retries happen inside sendWithRetries, so
     * the count is only visible to the caller if it is carried out by reference — and
     * "this one needed three tries" is exactly what makes a history row worth reading.
     */
    private static final class Attempted {
        private int attempts = 0;
    }

    /**
     * Wait a random interval drawn from [minDelayMs, maxDelayMs] before the next message.
     *
     * @return the stop that arrived while waiting, or {@link Stop#NONE} if none did
     */
    private Stop sleepRandomDelay(SettingsService.CirculationSettings cfg) {
        return sleepInterruptible(randomDelayMs(cfg));
    }

    private long randomDelayMs(SettingsService.CirculationSettings cfg) {
        long min = Math.max(0, cfg.minDelayMs());
        long max = Math.max(min, cfg.maxDelayMs());
        // nextLong's bound is exclusive, so +1 keeps maxDelayMs itself reachable.
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /**
     * Sleep in slices so a pause or cancel is picked up promptly instead of after the full
     * delay — the gap between runs is an hour, and a stop that took an hour to notice
     * would be indistinguishable from one that was ignored.
     *
     * <p>An interrupt means the process is going down, which is a pause: the run is
     * unfinished, not abandoned.
     */
    private Stop sleepInterruptible(long totalMs) {
        long remaining = totalMs;
        while (remaining > 0) {
            if (stopRequested != Stop.NONE) {
                return stopRequested;
            }
            long slice = Math.min(CANCEL_POLL_MS, remaining);
            try {
                TimeUnit.MILLISECONDS.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return stopRequested != Stop.NONE ? stopRequested : Stop.PAUSE;
            }
            remaining -= slice;
        }
        return stopRequested;
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
        // Host comes from Settings, which falls back to MAIL_HOST when it was never changed.
        if (!isSet(settings.circulation().smtpHost())) {
            missing.add("SMTP host (Settings, or MAIL_HOST)");
        }
        if (impl == null || !isSet(impl.getUsername())) {
            missing.add("MAIL_USERNAME");
        }
        if (impl == null || !isSet(impl.getPassword())) {
            missing.add("MAIL_PASSWORD");
        }
        if (!isSet(settings.circulation().fromAddress())) {
            missing.add("From address (Settings, or MAIL_FROM)");
        }
        return missing;
    }

    private void verifyConnection(JavaMailSenderImpl impl) {
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

    /**
     * A sender pointed at the host/port currently configured in Settings, carrying the
     * credentials and transport properties of the Spring-configured one.
     *
     * <p>A fresh instance rather than mutating the shared bean: the bean is a singleton, and
     * reconfiguring it in place would make the host depend on whoever sent last.
     *
     * <p>When the port is changed, the TLS mode moves with it by convention — 465 is
     * implicit SSL, anything else is STARTTLS — because the two are not independently
     * meaningful and a port change alone would otherwise just fail to connect. Leaving the
     * port at its configured value keeps whatever MAIL_SSL/MAIL_STARTTLS said, so an
     * environment tuned by hand is never second-guessed.
     */
    private JavaMailSenderImpl senderFor(SettingsService.CirculationSettings s) {
        JavaMailSenderImpl base = asImpl();
        if (base == null) {
            return null;
        }
        boolean unchanged = s.smtpPort() == base.getPort()
                && s.smtpHost() != null && s.smtpHost().equalsIgnoreCase(base.getHost());
        if (unchanged) {
            return base;
        }

        JavaMailSenderImpl out = new JavaMailSenderImpl();
        out.setHost(s.smtpHost());
        out.setPort(s.smtpPort());
        out.setUsername(base.getUsername());
        out.setPassword(base.getPassword());
        out.setProtocol(base.getProtocol());
        out.setDefaultEncoding(base.getDefaultEncoding());

        Properties p = new Properties();
        p.putAll(base.getJavaMailProperties());
        if (s.smtpPort() != base.getPort()) {
            boolean implicitSsl = s.smtpPort() == 465;
            p.setProperty("mail.smtp.ssl.enable", String.valueOf(implicitSsl));
            p.setProperty("mail.smtp.starttls.enable", String.valueOf(!implicitSsl));
        }
        // Certificate trust names a host, so it has to follow the host it was set for.
        p.setProperty("mail.smtp.ssl.trust", s.smtpHost());
        out.setJavaMailProperties(p);
        return out;
    }

    /**
     * Rough time left: the messages still to send at the mean gap, plus every quiet gap
     * still to come between runs — on a split campaign those pauses are most of the wait,
     * and an estimate that ignored them would be wrong by hours.
     */
    private Long etaSeconds(RunState s) {
        if (!running.get() || s.total() <= 0) {
            return null;
        }
        int done = s.sent() + s.failed();
        int remaining = Math.max(0, s.total() - done);
        SettingsService.CirculationSettings cfg = activeSettings != null ? activeSettings : settings.circulation();
        long ms = remaining * cfg.averageDelayMs();
        ms += (long) Math.max(0, s.batchCount() - s.batch()) * cfg.batchPauseMs();
        if (s.paused() && s.nextBatchAt() != null) {
            ms += Math.max(0, Duration.between(LocalDateTime.now(), s.nextBatchAt()).toMillis());
        }
        return ms / 1000;
    }

    /** One wording for a stop, wherever it is noticed - before, between or during a run. */
    private static String stoppedMessage(Stop stop, int sent, int total) {
        return stop == Stop.PAUSE
                ? "Paused after %d of %d message(s). Resume to send to the remaining %d."
                        .formatted(sent, total, Math.max(0, total - sent))
                : "Cancelled after %d of %d message(s).".formatted(sent, total);
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

    /**
     * A gap, in the largest unit that still reads exactly. Covers both scales this class
     * waits on: a retry backoff of a few seconds, and a pause between runs of an hour.
     */
    private static String formatDelay(long ms) {
        if (ms < 1000) {
            return ms + "ms";                       // sub-second must not render as "0s"
        }
        long seconds = ms / 1000;
        if (seconds < 90) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        return minutes < 90 ? minutes + "m" : "%dh %02dm".formatted(minutes / 60, minutes % 60);
    }

    /**
     * Immutable snapshot of progress — swapped wholesale so readers never see a torn state.
     *
     * <p>Counters are campaign-wide, not per run: "sent 137 of 256" is the question anyone
     * watching a split campaign is asking, and {@code batch}/{@code batchCount} say where
     * in the sequence that total stands.
     */
    private record RunState(String state, Long runId, String subject,
                            int total, int sent, int failed, int skipped,
                            String currentEmail, LocalDateTime startedAt, LocalDateTime finishedAt,
                            String lastError, String message,
                            int batch, int batchCount, boolean paused, LocalDateTime nextBatchAt) {

        static RunState idle() {
            return new RunState("IDLE", null, null, 0, 0, 0, 0, null, null, null, null, null,
                    0, 0, false, null);
        }

        /**
         * A sitting about to begin. The counters are seeded from what the run had already
         * done, so a resume picks the progress bar up where the pause left it rather than
         * dropping it back to zero.
         */
        static RunState starting(Job job) {
            return new RunState("RUNNING", job.record().runId(), job.subject(), job.total(),
                    job.alreadySent(), job.alreadyFailed(), job.skipped(), null,
                    LocalDateTime.now(), null, null, null,
                    1, job.cfg().batchCount(job.recipients().size()), false, null);
        }

        RunState progress(String email, int sent, int failed, int skipped) {
            return new RunState(state, runId, subject, total, sent, failed, skipped, email, startedAt, null,
                    lastError, message, batch, batchCount, false, null);
        }

        RunState withError(String error) {
            return new RunState(state, runId, subject, total, sent, failed, skipped, currentEmail, startedAt, null,
                    error, message, batch, batchCount, paused, nextBatchAt);
        }

        /** Between runs: still RUNNING, but nothing leaves until {@code nextBatchAt}. */
        RunState pausing(int nextBatch, LocalDateTime nextBatchAt) {
            return new RunState(state, runId, subject, total, sent, failed, skipped, null, startedAt, null,
                    lastError, message, nextBatch, batchCount, true, nextBatchAt);
        }

        RunState resumed() {
            return new RunState(state, runId, subject, total, sent, failed, skipped, null, startedAt, null,
                    lastError, message, batch, batchCount, false, null);
        }

        RunState finished(String finalState, int sent, int failed, int skipped, String finalMessage) {
            return new RunState(finalState, runId, subject, total, sent, failed, skipped, null,
                    startedAt, LocalDateTime.now(), lastError, finalMessage, batch, batchCount, false, null);
        }
    }
}
