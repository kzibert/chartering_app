package com.chartering.service;

import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.*;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.CirculationRun;
import com.chartering.model.CirculationRunRecipient;
import com.chartering.repository.CirculationRunRecipientRepository;
import com.chartering.repository.CirculationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    private final CirculationRunRepository runs;
    private final CirculationRunRecipientRepository recipients;
    private final MailTemplateService templates;
    private final MailCampaignProperties props;

    /**
     * A run still marked RUNNING at startup belonged to a process that no longer exists —
     * the API was restarted mid-send. Nothing will ever finish it, so it is closed here
     * rather than left looking permanently in flight in the History dropdown.
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
            run.setState("ABORTED");
            run.setFinishedAt(LocalDateTime.now());
            run.setMessage("Interrupted — the API restarted while this circulation was sending. "
                    + "Recipients still marked PENDING were never reached.");
            runs.save(run);
        }
        if (!stale.isEmpty()) {
            log.warn("Closed {} circulation run(s) left RUNNING by a previous process", stale.size());
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
                            List<CampaignRecipientRequest> duplicates,
                            List<CampaignRecipientRequest> notWorking) {
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
        run.setSkipped(duplicates.size() + notWorking.size());

        // Sendable first, so the recipient rows sit in the order the run works through them.
        toSend.forEach(r -> run.addRecipient(recipientRow(r, CirculationRunRecipient.PENDING)));
        duplicates.forEach(r -> run.addRecipient(
                recipientRow(r, CirculationRunRecipient.SKIPPED_DUPLICATE)));
        notWorking.forEach(r -> run.addRecipient(
                recipientRow(r, CirculationRunRecipient.SKIPPED_NOT_WORKING)));

        CirculationRun saved = runs.save(run);
        List<Long> sendableIds = saved.getRecipients().stream()
                .limit(toSend.size())
                .map(CirculationRunRecipient::getId)
                .toList();
        return new StartedRun(saved.getId(), sendableIds);
    }

    @Transactional
    public void recordSent(Long recipientId, int attempts) {
        recipients.recordOutcome(recipientId, CirculationRunRecipient.SENT, attempts, null,
                LocalDateTime.now());
    }

    @Transactional
    public void recordFailed(Long recipientId, int attempts, String error) {
        recipients.recordOutcome(recipientId, CirculationRunRecipient.FAILED, attempts, error, null);
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

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public PageResponse<CirculationRunResponse> history(Pageable pageable) {
        return PageResponse.from(runs.findAllByOrderByStartedAtDesc(pageable)
                .map(CirculationHistoryService::toRunResponse));
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

        CampaignRecipientRequest merge = new CampaignRecipientRequest(
                r.getEmail(), r.getContactId(), r.getGreetingName(), r.getPersonName(),
                r.getTitle(), r.getCompanyName());
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
        return new CirculationRunResponse(run.getId(), run.getSubjectTemplate(), run.getListName(),
                run.getFooterName(), run.getState(), run.getTotal(), run.getSent(), run.getFailed(),
                run.getSkipped(), run.getStartedAt(), run.getFinishedAt(), run.getMessage());
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
