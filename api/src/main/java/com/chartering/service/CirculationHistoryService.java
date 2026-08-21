package com.chartering.service;

import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.*;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.CirculationRun;
import com.chartering.model.CirculationRunRecipient;
import com.chartering.repository.CirculationRunRecipientRepository;
import com.chartering.repository.CirculationRunRepository;
import com.chartering.service.mail.BrevoStatsService;
import com.chartering.service.mail.CircularProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The circulation history: every run that was started, who it reached, and what each of
 * them received.
 *
 * <p>Storage shape is the point of this class. The composed circular is written <b>once</b>
 * per run, and each recipient stores only the merge fields it was rendered with. Because
 * {@link MailTemplateService} is a pure function of (template, fields), replaying it
 * reproduces the exact message that recipient got — so a 300-address run costs one copy of
 * the body instead of three hundred, and history stays cheap enough to keep forever.
 *
 * <p>Every method that a running campaign calls is its own transaction. The campaign worker
 * is a background thread that lives far longer than any sensible transaction, and a send
 * that has happened must be recorded even if a later one fails.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CirculationHistoryService {

    /** Stopped by hand, resumable. Set by the campaign; nothing else writes it. */
    public static final String PAUSED = "PAUSED";
    /** Stopped by an API restart, resumable. */
    public static final String INTERRUPTED = "INTERRUPTED";

    private final CirculationRunRepository runs;
    private final CirculationRunRecipientRepository recipients;
    private final MailTemplateService templates;
    private final MailCampaignProperties props;
    // Reporting only: the day counter pairs what this app sent with what Brevo says the
    // account has spent, and the second half is knowable only by asking Brevo.
    private final BrevoStatsService brevoStats;

    /**
     * A run still marked RUNNING at startup belonged to a process that no longer exists —
     * the API was restarted mid-send. Nothing in <em>this</em> process is sending it, so it
     * is taken out of flight here rather than left looking permanently in progress.
     *
     * <p>INTERRUPTED, not ABORTED: the recipient rows say exactly who was already reached,
     * so the run can be picked up where it stopped. {@code finishedAt} stays null because
     * the run has not finished — it is waiting for someone to resume or discard it.
     *
     * <p>The run-level counters are rebuilt from the rows first. They are normally written
     * once, when a run closes, and a run killed with its process never got that write.
     *
     * <p>Bound to ApplicationReadyEvent rather than @PostConstruct on purpose: the
     * transactional proxy is not in place while a bean is still initialising, so
     * {@code @Transactional} on a @PostConstruct method is silently ignored.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void closeInterruptedRuns() {
        List<CirculationRun> stale = runs.findByStateAndFinishedAtIsNull("RUNNING");
        for (CirculationRun run : stale) {
            run.setSent(recipients.countByRunIdAndStatus(run.getId(), CirculationRunRecipient.SENT));
            run.setFailed(recipients.countByRunIdAndStatus(run.getId(), CirculationRunRecipient.FAILED));
            run.setState(INTERRUPTED);
            run.setMessage("Interrupted — the API restarted while this circulation was sending. "
                    + "Everyone still marked PENDING was never reached; resume to send to them.");
            runs.save(run);
        }
        if (!stale.isEmpty()) {
            log.warn("Reopened {} circulation run(s) left RUNNING by a previous process as {}",
                    stale.size(), INTERRUPTED);
        }
    }

    // ---------------------------------------------------------------- recording

    /**
     * Open a run and queue every address it will touch, including the ones already ruled
     * out. Returns the recipient row ids in send order so the campaign can record outcomes
     * by id without re-querying.
     */
    @Transactional
    public StartedRun begin(String subject, String composedHtml, Long footerId, String footerName,
                            Long listId, String listName,
                            List<CampaignRecipientRequest> toSend,
                            SkippedRecipients skipped) {
        CirculationRun run = new CirculationRun();
        run.setSubjectTemplate(subject);
        run.setComposedHtml(composedHtml);
        run.setFooterId(footerId);
        run.setFooterName(footerName);
        run.setListId(listId);
        run.setListName(listName);
        run.setFromAddress(props.getFromAddress());
        run.setFromName(props.getFromName());
        run.setReplyTo(props.getReplyTo());
        run.setState("RUNNING");
        run.setTotal(toSend.size());
        run.setSkipped(skipped.total());

        // Sendable first, so the recipient rows sit in the order the run works through them.
        toSend.forEach(r -> run.addRecipient(recipientRow(r, CirculationRunRecipient.PENDING)));
        skipped.duplicates().forEach(r -> run.addRecipient(
                recipientRow(r, CirculationRunRecipient.SKIPPED_DUPLICATE)));
        skipped.notWorking().forEach(r -> run.addRecipient(
                recipientRow(r, CirculationRunRecipient.SKIPPED_NOT_WORKING)));
        skipped.notForCirc().forEach(r -> run.addRecipient(
                recipientRow(r, CirculationRunRecipient.SKIPPED_NOT_FOR_CIRC)));
        skipped.leftCompany().forEach(r -> run.addRecipient(
                recipientRow(r, CirculationRunRecipient.SKIPPED_LEFT_COMPANY)));

        CirculationRun saved = runs.save(run);
        List<Long> sendableIds = saved.getRecipients().stream()
                .limit(toSend.size())
                .map(CirculationRunRecipient::getId)
                .toList();
        return new StartedRun(saved.getId(), sendableIds);
    }

    @Transactional
    public void recordSent(Long recipientId, int attempts, CircularProvider provider) {
        recipients.recordOutcome(recipientId, CirculationRunRecipient.SENT, attempts, null,
                LocalDateTime.now(), provider.name());
    }

    @Transactional
    public void recordFailed(Long recipientId, int attempts, String error, CircularProvider provider) {
        recipients.recordOutcome(recipientId, CirculationRunRecipient.FAILED, attempts, error,
                null, provider.name());
    }

    /**
     * Close the run. Recipients still PENDING are left that way deliberately: on a cancelled
     * or aborted run, "never reached" is the fact you need, and rewriting those rows to
     * anything else would lose it.
     */
    @Transactional
    public void finish(Long runId, String state, int sent, int failed, int skipped,
                       String message, String lastError) {
        runs.findById(runId).ifPresent(run -> {
            run.setState(state);
            run.setSent(sent);
            run.setFailed(failed);
            run.setSkipped(skipped);
            run.setMessage(message);
            run.setLastError(lastError);
            run.setFinishedAt(LocalDateTime.now());
            runs.save(run);
        });
    }

    /**
     * Take the run out of flight without ending it. Everyone still PENDING stays PENDING,
     * which is precisely what makes the run resumable — and {@code finishedAt} stays null,
     * so a run paused now is still paused after an API restart rather than being swept up
     * as an interrupted one.
     */
    @Transactional
    public void pause(Long runId, int sent, int failed, int skipped, String message) {
        runs.findById(runId).ifPresent(run -> {
            run.setState(PAUSED);
            run.setSent(sent);
            run.setFailed(failed);
            run.setSkipped(skipped);
            run.setMessage(message);
            run.setFinishedAt(null);
            runs.save(run);
        });
    }

    /**
     * Put a stopped run back in flight for a resume. The totals are passed in because a
     * resume re-checks the remaining addresses against the not-working flags, and anyone
     * dropped there moves from the run's total to its skipped count.
     */
    @Transactional
    public void reopen(Long runId, int total, int skipped) {
        runs.findById(runId).ifPresent(run -> {
            run.setState("RUNNING");
            run.setTotal(total);
            run.setSkipped(skipped);
            run.setFinishedAt(null);
            run.setMessage(null);
            runs.save(run);
        });
    }

    /**
     * Addresses dropped as a resume begins, because a flag has been set since the run
     * started. The status carries which flag, so history keeps saying why rather than
     * collapsing two quite different reasons into one.
     */
    @Transactional
    public void markSkipped(List<Long> recipientIds, String status) {
        for (Long id : recipientIds) {
            recipients.markSkipped(id, status);
        }
    }

    /**
     * The addresses a run filtered out before sending, kept apart by reason.
     *
     * <p>Grouped into one object rather than passed as same-typed lists: they are always
     * supplied together, and four adjacent {@code List<CampaignRecipientRequest>} parameters
     * are four chances to record everyone as a duplicate.
     */
    public record SkippedRecipients(List<CampaignRecipientRequest> duplicates,
                                    List<CampaignRecipientRequest> notWorking,
                                    List<CampaignRecipientRequest> notForCirc,
                                    List<CampaignRecipientRequest> leftCompany) {

        public int total() {
            return duplicates.size() + notWorking.size() + notForCirc.size() + leftCompany.size();
        }
    }

    // ---------------------------------------------------------------- resuming and restarting

    /**
     * Everything needed to carry a stopped run on from where it stopped: the circular as
     * it was composed then, and the addresses still marked PENDING in send order.
     *
     * <p>The stored {@code composedHtml} is used as-is rather than recomposed from the
     * footer — the second half of a circular has to read like the first, even if the
     * footer was edited or deleted in between.
     */
    @Transactional(readOnly = true)
    public ResumableRun loadForResume(Long runId) {
        CirculationRun run = findWithRecipients(runId);
        List<PendingRecipient> pending = run.getRecipients().stream()
                .filter(r -> CirculationRunRecipient.PENDING.equals(r.getStatus()))
                .map(r -> new PendingRecipient(r.getId(), toMergeFields(r)))
                .toList();
        return new ResumableRun(run.getId(), run.getSubjectTemplate(), run.getComposedHtml(),
                run.getListName(), pending, run.getSent(), run.getFailed(), run.getSkipped());
    }

    /**
     * Everything needed to send a past run again from the top, as a new run of its own.
     *
     * <p>Duplicates are left out — they were dropped as duplicates of addresses that are
     * in the list, and re-adding them would only have them dropped again. Not-working ones
     * <em>are</em> included: a flag cleared since is a reason to try that address again,
     * and one still set is re-applied when the new run starts.
     */
    @Transactional(readOnly = true)
    public RestartableRun loadForRestart(Long runId) {
        CirculationRun run = findWithRecipients(runId);
        List<CampaignRecipientRequest> all = run.getRecipients().stream()
                .filter(r -> !CirculationRunRecipient.SKIPPED_DUPLICATE.equals(r.getStatus()))
                .map(CirculationHistoryService::toMergeFields)
                .toList();
        return new RestartableRun(run.getSubjectTemplate(), run.getComposedHtml(),
                run.getFooterId(), run.getFooterName(), run.getListId(), run.getListName(), all);
    }

    /** Runs with somebody still to send to, newest first. */
    @Transactional(readOnly = true)
    public List<CirculationRunResponse> resumable() {
        return runs.findResumable().stream()
                .map(CirculationHistoryService::toRunResponse)
                .toList();
    }

    /** One address a resume still has to reach, and the history row that records it. */
    public record PendingRecipient(Long recipientId, CampaignRecipientRequest fields) {
    }

    /** A stopped run, with what it already achieved and who is left. */
    public record ResumableRun(Long runId, String subject, String composedHtml, String listName,
                               List<PendingRecipient> pending,
                               int alreadySent, int alreadyFailed, int alreadySkipped) {
    }

    /** A past run, ready to be sent again as a fresh one. */
    public record RestartableRun(String subject, String composedHtml, Long footerId, String footerName,
                                 Long listId, String listName, List<CampaignRecipientRequest> recipients) {
    }

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public PageResponse<CirculationRunResponse> history(Pageable pageable) {
        return PageResponse.from(runs.findAllByOrderByStartedAtDesc(pageable)
                .map(CirculationHistoryService::toRunResponse));
    }

    /**
     * What has actually gone out today. The pacing rails guard the mailbox's per-hour
     * allowance and nothing guards the daily one — so this is the figure to read before
     * starting another circular, since exceeding a provider's daily cap can suspend
     * outgoing mail on the whole account.
     *
     * <p>Counted from each address's own send time rather than from the run counters: a run
     * that started last night, or one resumed this morning, has to put its messages on the
     * day they really left. "Today" is the server's local day — the number is read against
     * a cap that is felt during working hours, not at 02:00 UTC.
     */
    @Transactional(readOnly = true)
    public CirculationTodayResponse today() {
        LocalDate day = LocalDate.now();
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime until = from.plusDays(1);

        Map<CircularProvider, Integer> byProvider = sentTodayByProvider(from, until);
        int viaMailbox = byProvider.getOrDefault(CircularProvider.SMTP, 0);
        int viaBrevo = byProvider.getOrDefault(CircularProvider.BREVO, 0);

        // Summed from the breakdown rather than counted again: two queries a moment apart can
        // straddle a message going out, and a total that disagrees with its own parts is worse
        // than a total that is a second stale.
        int sent = viaMailbox + viaBrevo;

        BrevoStatsService.BrevoUsage usage = brevoStats.today();
        return new CirculationTodayResponse(day, sent,
                recipients.countRunsSendingBetween(CirculationRunRecipient.SENT, from, until),
                viaMailbox, viaBrevo,
                usage.configured() ? new BrevoUsageResponse(usage.sent(), usage.blocked(),
                        usage.remaining(), usage.dailyLimit(), usage.error()) : null);
    }

    /**
     * Today's sends grouped by the flow they left through.
     *
     * <p>Unknown provider strings are folded into the mailbox flow rather than dropped: the
     * only way to hold one is to predate the column, and everything that predates it went out
     * over SMTP because that was the only route there was. Dropping them would silently shrink
     * the day's total on the morning after an upgrade.
     */
    private Map<CircularProvider, Integer> sentTodayByProvider(LocalDateTime from, LocalDateTime until) {
        Map<CircularProvider, Integer> out = new EnumMap<>(CircularProvider.class);
        for (Object[] row : recipients.countSentByProviderBetween(
                CirculationRunRecipient.SENT, from, until)) {
            CircularProvider provider = CircularProvider.parse((String) row[0]);
            int count = ((Number) row[1]).intValue();
            out.merge(provider, count, Integer::sum);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public CirculationRunDetailResponse detail(Long runId) {
        CirculationRun run = findWithRecipients(runId);
        List<CirculationRunRecipientResponse> rows = run.getRecipients().stream()
                .map(CirculationHistoryService::toRecipientResponse)
                .toList();
        return new CirculationRunDetailResponse(toRunResponse(run), run.getComposedHtml(),
                run.getFromAddress(), run.getFromName(), run.getReplyTo(), run.getLastError(), rows);
    }

    /**
     * Reproduce exactly what one recipient received, by replaying the run's stored merge
     * against that recipient's stored fields — the same call the sender made, with the same
     * inputs, so the output is the message itself rather than an approximation of it.
     */
    @Transactional(readOnly = true)
    public CirculationMessageResponse message(Long runId, Long recipientId) {
        CirculationRun run = findWithRecipients(runId);
        CirculationRunRecipient r = run.getRecipients().stream()
                .filter(x -> x.getId().equals(recipientId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Circulation recipient", recipientId));

        CampaignRecipientRequest merge = toMergeFields(r);
        String html = templates.renderHtml(run.getComposedHtml(), merge);
        return new CirculationMessageResponse(
                r.getEmail(),
                r.getPersonName(),
                templates.renderText(run.getSubjectTemplate(), merge),
                html,
                templates.htmlToText(html));
    }

    @Transactional
    public void delete(Long runId) {
        CirculationRun run = runs.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Circulation run", runId));
        runs.delete(run);
    }

    private CirculationRun findWithRecipients(Long runId) {
        return runs.findByIdWithRecipients(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Circulation run", runId));
    }

    // ---------------------------------------------------------------- mapping

    /** A recipient row read back as the merge fields it was rendered with. */
    private static CampaignRecipientRequest toMergeFields(CirculationRunRecipient r) {
        return new CampaignRecipientRequest(r.getEmail(), r.getContactId(), r.getGreetingName(),
                r.getPersonName(), r.getTitle(), r.getCompanyName());
    }

    private static CirculationRunRecipient recipientRow(CampaignRecipientRequest r, String status) {
        CirculationRunRecipient row = new CirculationRunRecipient();
        row.setEmail(r.getEmail());
        row.setContactId(r.getContactId());
        row.setPersonName(r.getPersonName());
        row.setGreetingName(r.getGreetingName());
        row.setTitle(r.getTitle());
        row.setCompanyName(r.getCompanyName());
        row.setStatus(status);
        return row;
    }

    private static CirculationRunResponse toRunResponse(CirculationRun run) {
        // Derived rather than counted: sent + failed + pending is the run's total by
        // construction, and the alternative is a count query per row of the History list.
        int pending = Math.max(0, run.getTotal() - run.getSent() - run.getFailed());
        boolean resumable = pending > 0 && !"RUNNING".equals(run.getState());
        return new CirculationRunResponse(run.getId(), run.getSubjectTemplate(), run.getListName(),
                run.getFooterName(), run.getState(), run.getTotal(), run.getSent(), run.getFailed(),
                run.getSkipped(), pending, resumable,
                run.getStartedAt(), run.getFinishedAt(), run.getMessage());
    }

    private static CirculationRunRecipientResponse toRecipientResponse(CirculationRunRecipient r) {
        return new CirculationRunRecipientResponse(r.getId(), r.getEmail(), r.getContactId(),
                r.getPersonId(), r.getPersonName(), r.getGreetingName(), r.getTitle(),
                r.getCompanyId(), r.getCompanyName(), r.getStatus(), r.getAttempts(),
                r.getError(), r.getSentAt());
    }

    /** The run id plus its sendable recipient row ids, in send order. */
    public record StartedRun(Long runId, List<Long> recipientIds) {

        /** An empty run that records nothing — used when history could not be opened. */
        public static StartedRun none() {
            return new StartedRun(null, new ArrayList<>());
        }

        public boolean recording() {
            return runId != null;
        }
    }
}
