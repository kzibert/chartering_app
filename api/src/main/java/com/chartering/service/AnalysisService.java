package com.chartering.service;

import com.chartering.config.AnalysisProperties;
import com.chartering.config.MailboxProperties;
import com.chartering.dto.AnalysisCaptureRequest;
import com.chartering.dto.AnalysisCaptureResponse;
import com.chartering.dto.AnalysisPasteRequest;
import com.chartering.dto.AnalysisSampleDetailResponse;
import com.chartering.dto.AnalysisSampleResponse;
import com.chartering.dto.AnalysisSampleUpdateRequest;
import com.chartering.dto.AnalysisStatusResponse;
import com.chartering.dto.PageResponse;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.AnalysisLabel;
import com.chartering.model.AnalysisSample;
import com.chartering.model.AnalysisStatus;
import com.chartering.model.MailMessage;
import com.chartering.repository.AnalysisSampleRepository;
import com.chartering.repository.MailMessageRepository;
import com.chartering.service.mail.MailServerFolderService;
import com.chartering.specification.AnalysisSampleSpecification;
import com.chartering.specification.MailMessageSpecification;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The training corpus: capturing incoming mail, labelling it, and keeping it fit to export.
 *
 * <p><b>What this is for.</b> A broker's inbox is the only place a real cargo offer or a
 * real position list can be had in quantity, and both are written in a shorthand no
 * general-purpose model reads reliably out of the box. Teaching one means examples — the
 * email as it arrived, and beside it what a person says it means. Everything here exists to
 * collect those pairs and to keep bad ones out of a training file.
 *
 * <p><b>The gate.</b> {@link #requireEnabled()} guards every method except {@link #status()},
 * which has to answer either way — it is what the UI asks before deciding whether the tab
 * exists at all. Off, the rest is 404: not "you may not", not "not yet", but genuinely not
 * part of this deployment. See {@link AnalysisProperties} for why the hosted one runs
 * without it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisService {

    /** How much of the body a list row previews. One line, as in the mailbox. */
    private static final int SNIPPET_CHARS = 200;

    private final AnalysisProperties props;
    private final MailboxProperties mailboxProps;
    private final AnalysisSampleRepository samples;
    private final MailMessageRepository messages;
    private final MailServerFolderService serverFolders;
    private final ObjectMapper json;
    private final DtoMapper mapper;

    // ------------------------------------------------------------------ status

    /**
     * Whether the feature is here, and — when it is — what the corpus looks like.
     *
     * <p>Deliberately the one method that does not call {@link #requireEnabled()}. A UI that
     * could not ask this would have to discover the feature is off by calling something else
     * and reading the 404, which is how a disabled feature ends up showing an error toast on
     * every page load.
     */
    @Transactional(readOnly = true)
    public AnalysisStatusResponse status() {
        if (!props.isEnabled()) {
            // Nothing counted and nothing claimed: every other field stays null and is
            // dropped from the JSON, so a deployment running without this does not publish
            // the size of a corpus it is not keeping.
            return new AnalysisStatusResponse(false, null, null, null, null, null, null,
                    null, null, null, null, null);
        }

        Map<String, Long> byLabel = countsByName(samples.countByLabel());
        Map<String, Long> byStatus = countsByName(samples.countByStatus());
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long synced = messages.count();
        long ready = byStatus.getOrDefault(AnalysisStatus.READY.name(), 0L);

        // All three are ordinary states of a corpus being started rather than faults, which
        // is why each is worded as the next thing to do rather than as an error.
        List<String> warnings = new ArrayList<>();
        if (!mailboxProps.isEnabled()) {
            warnings.add("The mailbox is not being synced (IMAP_ENABLED), so there is no "
                    + "incoming mail to capture. Samples can still be pasted in by hand.");
        } else if (synced == 0) {
            warnings.add("No mail has been synced yet — run a sync on the Mailbox tab first.");
        }
        if (total > 0 && ready == 0) {
            warnings.add("Nothing is marked ready, so an export would be empty. A sample "
                    + "becomes ready once it has a label and an annotation.");
        }

        return new AnalysisStatusResponse(
                true,
                total,
                ready,
                byLabel,
                byStatus,
                AnalysisAnnotationTemplates.all(),
                AnalysisAnnotationTemplates.SYSTEM_PROMPT,
                props.getMaxBodyChars(),
                props.getMaxCapturePerRun(),
                mailboxProps.isEnabled(),
                synced,
                warnings);
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    // ----------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public PageResponse<AnalysisSampleResponse> search(SampleFilter f, Pageable pageable) {
        requireEnabled();
        Specification<AnalysisSample> spec = Specification.allOf(
                AnalysisSampleSpecification.matches(f.search()),
                AnalysisSampleSpecification.hasLabel(f.label()),
                AnalysisSampleSpecification.hasStatus(f.status()),
                AnalysisSampleSpecification.hasSource(f.source()),
                AnalysisSampleSpecification.receivedFrom(f.receivedFrom()),
                AnalysisSampleSpecification.receivedTo(f.receivedTo()));
        return PageResponse.from(samples.findAll(spec, pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public AnalysisSampleDetailResponse getDetail(Long id) {
        requireEnabled();
        return toDetail(find(id));
    }

    /**
     * The rows an export reads. Unpaged on purpose: a training file is one document, not a
     * page of one, and the corpus is thousands of rows rather than the mailbox's hundreds of
     * thousands.
     */
    @Transactional(readOnly = true)
    public List<AnalysisSample> readyForExport() {
        requireEnabled();
        return samples.findByStatusOrderByIdAsc(AnalysisStatus.READY);
    }

    // ----------------------------------------------------------------- writing

    /**
     * Capture: copy matching synced mail into the corpus.
     *
     * <p>Three things it deliberately does not do. It does not touch the mail — the mailbox
     * is opened read-only and a capture must leave no mark on it, not a flag and not a move.
     * It does not label anything: everything lands {@code UNLABELLED}/{@code NEW}, because a
     * capture that guessed would produce a corpus whose labels are the guess rather than a
     * judgement, and nobody would ever find the ones it got wrong. And it never overwrites a
     * sample already here — a second run over the same folder adds only what is new, which is
     * what makes "capture again, I have synced more" a safe habit rather than a decision.
     */
    @Transactional
    public AnalysisCaptureResponse capture(AnalysisCaptureRequest req) {
        requireEnabled();

        Specification<MailMessage> spec = Specification.allOf(
                MailMessageSpecification.matches(
                        req.search(), Boolean.TRUE.equals(req.searchBody())),
                MailMessageSpecification.inFolder(req.folderId()),
                MailMessageSpecification.inServerFolder(
                        req.imapFolder(),
                        req.imapFolder() == null ? null : serverFolders.separator()),
                MailMessageSpecification.receivedFrom(req.receivedFrom()),
                MailMessageSpecification.receivedTo(req.receivedTo()));

        int limit = clampLimit(req.limit());
        // Newest first: the mail worth learning from is the mail the desk is working with
        // now, so a run that stops at its cap should have taken the recent end of the range.
        Page<MailMessage> page = messages.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "receivedAt")));
        List<MailMessage> candidates = page.getContent();

        // One query for what is already here rather than one per candidate. Both identities
        // are asked about: the Message-ID normally, and the message's own id for the rare
        // message that arrived without one.
        Set<String> seenMessageIds = new HashSet<>(samples.findExistingMessageIds(
                candidates.stream().map(MailMessage::getMessageId).filter(Objects::nonNull).toList()));
        Set<Long> seenMailIds = new HashSet<>(samples.findExistingMailMessageIds(
                candidates.stream().map(MailMessage::getId).toList()));

        String user = currentUser();
        List<AnalysisSample> batch = new ArrayList<>();
        List<String> examples = new ArrayList<>();
        int alreadyPresent = 0;
        int skippedEmpty = 0;

        for (MailMessage m : candidates) {
            // add() returning false is both the "already in the corpus" test and the guard
            // against one run capturing the same message twice, which two rows sharing a
            // Message-ID in the mailbox would otherwise do.
            if (m.getMessageId() != null && !seenMessageIds.add(m.getMessageId())) {
                alreadyPresent++;
                continue;
            }
            if (!seenMailIds.add(m.getId())) {
                alreadyPresent++;
                continue;
            }
            // bodyText, never the HTML part: the two carry the same words and the markup is
            // noise. The sync extracts text even from an HTML-only message, so a blank here
            // means the message really held nothing — an attachment-only position list, a
            // calendar invite — and there is no sample to be made of it.
            String body = trimBody(m.getBodyText());
            if (body == null) {
                skippedEmpty++;
                continue;
            }

            AnalysisSample s = new AnalysisSample();
            s.setMailMessage(m);
            s.setSource(AnalysisSample.SOURCE_MAILBOX);
            s.setMessageId(m.getMessageId());
            s.setFromAddress(m.getFromAddress());
            s.setFromName(m.getFromName());
            s.setSubject(m.getSubject());
            s.setSentAt(m.getSentAt());
            s.setReceivedAt(m.getReceivedAt());
            s.setBodyText(body);
            s.setAttachmentNames(m.getAttachmentNames());
            s.setCreatedBy(user);
            batch.add(s);
            if (examples.size() < 5 && m.getSubject() != null) examples.add(m.getSubject());
        }

        samples.saveAll(batch);
        log.info("Analysis capture: {} matched, {} captured, {} already present, {} empty",
                page.getTotalElements(), batch.size(), alreadyPresent, skippedEmpty);

        return new AnalysisCaptureResponse(
                page.getTotalElements(), batch.size(), alreadyPresent, skippedEmpty,
                page.getTotalElements() > limit, examples);
    }

    /** One email added by hand — see {@link AnalysisPasteRequest} for when that is the way in. */
    @Transactional
    public AnalysisSampleDetailResponse paste(AnalysisPasteRequest req) {
        requireEnabled();
        String body = trimBody(req.bodyText());
        if (body == null) throw new IllegalArgumentException("The email text is empty.");

        AnalysisSample s = new AnalysisSample();
        s.setSource(AnalysisSample.SOURCE_PASTED);
        s.setFromAddress(blankToNull(req.fromAddress()));
        s.setFromName(blankToNull(req.fromName()));
        s.setSubject(blankToNull(req.subject()));
        // No Message-ID: a pasted sample has no identity on any server, and inventing one
        // would put a value in the dedupe column that matches nothing and protects nothing.
        s.setReceivedAt(req.receivedAt() != null ? req.receivedAt() : LocalDateTime.now());
        s.setBodyText(body);
        s.setNotes(blankToNull(req.notes()));
        s.setCreatedBy(currentUser());
        return toDetail(samples.save(s));
    }

    /**
     * A review: the label, the status, the annotation, the note. Never the email — a sample's
     * body is a snapshot of what arrived, and a corpus whose inputs have been edited is a
     * record of nothing.
     *
     * <p>A null field is left alone, so the form can send one changed field without holding
     * the rest. An empty-string annotation clears it, which is how "I was wrong about this
     * one" is said without deleting the sample and capturing it again on the next run.
     */
    @Transactional
    public AnalysisSampleDetailResponse update(Long id, AnalysisSampleUpdateRequest req) {
        requireEnabled();
        AnalysisSample s = find(id);

        if (req.label() != null) s.setLabel(req.label());
        if (req.notes() != null) s.setNotes(blankToNull(req.notes()));
        if (req.annotation() != null) {
            String annotation = blankToNull(req.annotation());
            if (annotation != null) validateJson(annotation);
            s.setAnnotation(annotation);
        }
        if (req.status() != null) {
            // READY is the only status the export reads, so it is the one worth guarding.
            // Refused here rather than filtered out at export time: the user learns the
            // moment they are wrong instead of finding a shorter file than they expected.
            if (req.status() == AnalysisStatus.READY) {
                if (s.getLabel() == AnalysisLabel.UNLABELLED) {
                    throw new IllegalArgumentException(
                            "Say what kind of email this is before marking it ready to train on.");
                }
                if (s.getAnnotation() == null) {
                    throw new IllegalArgumentException(
                            "A sample with no annotation has no answer to train against.");
                }
            }
            s.setStatus(req.status());
        }
        return toDetail(s);
    }

    @Transactional
    public void delete(Long id) {
        requireEnabled();
        samples.delete(find(id));
    }

    // --------------------------------------------------------------- internals

    private AnalysisSample find(Long id) {
        return samples.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Analysis sample", id));
    }

    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new FeatureDisabledException(
                    "Email analysis is not enabled on this deployment (ANALYSIS_ENABLED).");
        }
    }

    private int clampLimit(Integer requested) {
        int max = Math.max(1, props.getMaxCapturePerRun());
        if (requested == null || requested <= 0) return max;
        return Math.min(requested, max);
    }

    /**
     * The body as it will be stored, or null when there is nothing worth keeping.
     *
     * <p>The cap is a cap and not a target: what runs past it is a quoted chain, a legal
     * disclaimer and a signature block rendered as text, none of which teaches a model
     * anything about a cargo. Truncation is silent by design — the reviewer reads the stored
     * text, so what they label is exactly what will be trained on.
     */
    private String trimBody(String body) {
        if (body == null || body.isBlank()) return null;
        String trimmed = body.strip();
        return trimmed.length() <= props.getMaxBodyChars()
                ? trimmed
                : trimmed.substring(0, props.getMaxBodyChars());
    }

    /**
     * Checked here so it cannot fail there: an annotation that does not parse becomes a line
     * of a training file no trainer can read, discovered by whatever consumes the export
     * rather than by the person who typed it.
     */
    private void validateJson(String annotation) {
        try {
            json.readTree(annotation);
        } catch (Exception e) {
            throw new IllegalArgumentException("The annotation is not valid JSON: "
                    + e.getMessage());
        }
    }

    private static Map<String, Long> countsByName(List<Object[]> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            out.put(row[0].toString(), ((Number) row[1]).longValue());
        }
        return out;
    }

    private AnalysisSampleResponse toResponse(AnalysisSample s) {
        return mapper.toAnalysisSampleResponse(s, snippet(s.getBodyText()));
    }

    private AnalysisSampleDetailResponse toDetail(AnalysisSample s) {
        return new AnalysisSampleDetailResponse(toResponse(s), s.getBodyText(), s.getAnnotation());
    }

    /** First line or so, newlines flattened, so a list row stays one line high. */
    private static String snippet(String body) {
        if (body == null) return null;
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? null : auth.getName();
    }

    /**
     * What the corpus list is narrowed by.
     *
     * @param search       one box over sender, subject, body and the reviewer's own notes
     * @param label        one kind of email
     * @param status       how far through review
     * @param source       MAILBOX or PASTED
     * @param receivedFrom when the email arrived, not when it was captured — a corpus is
     *                     reasoned about by the period it covers
     */
    public record SampleFilter(
            String search,
            AnalysisLabel label,
            AnalysisStatus status,
            String source,
            LocalDateTime receivedFrom,
            LocalDateTime receivedTo) {
    }
}
