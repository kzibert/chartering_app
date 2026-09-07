package com.chartering.service.parser;

import com.chartering.config.MailboxProperties;
import com.chartering.config.ParserProperties;
import com.chartering.dto.CargoSourceResponse;
import com.chartering.dto.IntakeItemResponse;
import com.chartering.dto.IntakeStatusResponse;
import com.chartering.dto.PageResponse;
import com.chartering.dto.ParsedEmailResponse;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.IntakeItem;
import com.chartering.model.IntakeItemKind;
import com.chartering.model.IntakeItemStatus;
import com.chartering.model.ParseStatus;
import com.chartering.model.ParsedEmail;
import com.chartering.repository.CargoSourceRepository;
import com.chartering.repository.IntakeItemRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.chartering.dto.VesselLookupResponse;
import com.chartering.model.Vessel;
import com.chartering.model.VesselLookup;
import com.chartering.repository.VesselRepository;
import com.chartering.service.ParserSettings;
import com.chartering.service.lookup.LookupFields;
import com.chartering.service.lookup.LookupMatcher;
import com.chartering.service.lookup.VesselLookupService;
import com.chartering.service.lookup.VesselParticulars;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the Intake tab reads.
 *
 * <p>Split from {@link IntakeService}, which decides and writes. The two are one feature and
 * deliberately not one class: applying a parse is the part with the judgement in it and is
 * worth reading on its own, while this is a screen's worth of queries and DTO assembly. The
 * split is the same one {@code CirculationHistoryService} makes against the service that
 * sends.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntakeQueryService {

    private final ParserProperties props;
    private final MailboxProperties mailboxProps;
    private final ParserSettings settings;
    private final ParserSweepService sweeps;
    private final EmailParserClient client;
    private final IntakeItemRepository items;
    private final ParsedEmailRepository parsedEmails;
    private final CargoSourceRepository cargoSources;
    private final IntakeService intake;
    private final VesselLookupService lookups;
    private final VesselRepository vessels;
    private final DtoMapper mapper;
    private final ObjectMapper json;

    // ------------------------------------------------------------------ status

    /**
     * Whether the feature is here, and — when it is — how it is doing.
     *
     * <p>The one method that does not check the switch, for the reason
     * {@code AnalysisService#status} does not: it is what the UI asks before deciding whether
     * the tab exists, and a UI that could only find out by calling something else and reading
     * a 404 shows an error toast on every page load.
     *
     * <p>It does reach the model server, which makes it the slowest status endpoint in the
     * app — bounded by the connect timeout, which is short precisely because of this. The
     * alternative is a tab that says nothing about the one thing most likely to be wrong.
     */
    @Transactional(readOnly = true)
    public IntakeStatusResponse status() {
        if (!props.isEnabled()) return IntakeStatusResponse.disabled();

        boolean reachable = client.isReachable();
        ParserSettings.Values values = settings.values();
        IntakeService.Counts counts = intake.counts();
        long unparsed = parsedEmails.countUnparsed(values.receivedSince());
        ParserSweepService.SweepReport last = sweeps.lastReport();

        // Each one is the next thing to do rather than an error, because every one of them is
        // an ordinary state of a feature being set up.
        List<String> warnings = new ArrayList<>();
        if (!reachable) {
            warnings.add("The model server at " + props.getUrl() + " is not answering. Start it "
                    + "with: docker compose -f serve/docker-compose.llamacpp.yml up -d "
                    + "(in the chartering-ml project).");
        }
        if (!mailboxProps.isEnabled()) {
            warnings.add("The mailbox is not being synced (IMAP_ENABLED), so no mail is "
                    + "arriving to read.");
        } else if (unparsed == 0 && parsedEmails.count() == 0) {
            warnings.add("No mail has been read yet — run a sync on the Mailbox tab, then "
                    + "press Parse now.");
        }
        if (values.sweepMaxAgeDays() > 0 && unparsed == 0
                && parsedEmails.countUnparsed(ParserSettings.NO_LIMIT_SINCE) > 0) {
            // The one case where the counter reads zero and there is genuinely work: older
            // mail outside the window. Worth saying, because widening the window is the fix
            // and nothing else on the screen hints at it.
            warnings.add("Only mail from the last " + values.sweepMaxAgeDays()
                    + " days is read. There is older unread mail — widen the lookback on the "
                    + "Settings tab to reach it.");
        }
        if (values.sweepIntervalMinutes() == 0) {
            warnings.add("The timer is off. Mail is only read when you press Parse now.");
        }

        return new IntakeStatusResponse(
                true,
                reachable,
                props.getUrl(),
                reachable ? null : client.lastError(),
                sweeps.isRunning(),
                counts.pending(), counts.accepted(), counts.rejected(),
                unparsed,
                parsedEmails.countByStatus(ParseStatus.PARSED),
                parsedEmails.countByStatus(ParseStatus.FAILED),
                values.sweepIntervalMinutes(),
                values.sweepBatchSize(),
                values.sweepMaxAgeDays(),
                sweeps.lastSweepAt(),
                sweeps.nextSweepAt(),
                last == null ? null : last.message(),
                warnings);
    }

    // ------------------------------------------------------------------ the queue

    @Transactional(readOnly = true)
    public PageResponse<IntakeItemResponse> search(IntakeItemKind kind, IntakeItemStatus status,
                                                   Pageable pageable) {
        requireEnabled();
        Specification<IntakeItem> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (kind != null) where.add(cb.equal(root.get("kind"), kind));
            if (status != null) where.add(cb.equal(root.get("status"), status));
            return where.isEmpty() ? null : cb.and(where.toArray(new Predicate[0]));
        };
        Page<IntakeItem> page = items.findAll(spec, pageable);
        return PageResponse.from(page.map(i -> toResponse(i, null)));
    }

    /**
     * One item, with whatever an outside source found about the hull.
     *
     * <p>The lookup rides on the detail call and not on the list: it carries every candidate
     * a search returned, and a page of twenty rows would be mostly that.
     */
    @Transactional(readOnly = true)
    public IntakeItemResponse get(Long id) {
        requireEnabled();
        IntakeItem item = load(id);
        return toResponse(item, lookupFor(item));
    }

    /** What the source said about this item's hull, assembled for the drawer. */
    public VesselLookupResponse lookupFor(IntakeItem item) {
        if (!lookups.isEnabled()) return null;
        VesselLookup row = lookups.forItem(item.getId()).orElse(null);
        if (row == null) return null;

        VesselParticulars matched = lookups.matched(row).orElse(null);
        List<VesselParticulars> candidates = lookups.candidates(row);

        List<String> reasons = List.of();
        List<String> disagreements = List.of();
        Boolean corroborated = null;
        List<LookupFields.Proposal> proposals = List.of();
        Long onFileId = null;
        String onFileName = null;

        if (matched != null) {
            // Scored against exactly the facts the lookup itself used - asking the service
            // rather than rebuilding them here. A second, thinner set would print weaker
            // evidence than the stored confidence was earned on, which is precisely the
            // contradiction this once shipped with: "name only, uncorroborated" beside 63%.
            Vessel vessel = item.getVesselId() == null ? null
                    : vessels.findById(item.getVesselId()).orElse(null);
            VesselLookupService.Known known = lookups.knownFacts(item);
            LookupMatcher.Known facts = known != null ? known.facts()
                    : new LookupMatcher.Known(item.getSubjectLabel(), null, null, null);
            LookupMatcher.Scored scored = LookupMatcher.score(facts, List.of(matched)).get(0);
            reasons = scored.reasons();
            disagreements = scored.disagreements();
            corroborated = scored.corroborated();
            proposals = LookupFields.proposals(vessel, matched);

            Vessel onFile = lookups.alreadyOnFile(row).orElse(null);
            if (onFile != null && !onFile.getId().equals(item.getVesselId())) {
                onFileId = onFile.getId();
                onFileName = onFile.getName();
            }
        }

        return new VesselLookupResponse(
                row.getId(), row.getProvider(), row.getQuery(), row.getStatus(),
                row.getConfidence(), corroborated, row.getSourceUrl(), row.getError(),
                row.getFetchedAt(),
                matched, reasons, disagreements, onFileId, onFileName, proposals, candidates);
    }

    /** Which fields a {@code VESSEL_FIELDS} accept may name, for the UI to render and check. */
    public java.util.Map<String, String> vesselFields() {
        requireEnabled();
        return VesselFieldDiff.fields();
    }

    // ------------------------------------------------------------------ the log

    @Transactional(readOnly = true)
    public PageResponse<ParsedEmailResponse> parsed(ParseStatus status, Pageable pageable) {
        requireEnabled();
        Page<ParsedEmail> page = status == null
                ? parsedEmails.findAllByOrderByParsedAtDesc(pageable)
                : parsedEmails.findByStatusOrderByParsedAtDesc(status, pageable);
        // No rawJson in the list: it is the model's whole answer and a page of twenty would
        // be a megabyte of JSON to render a table of counts.
        return PageResponse.from(page.map(p -> mapper.toParsedEmailResponse(p, null)));
    }

    @Transactional(readOnly = true)
    public ParsedEmailResponse parsedDetail(Long id) {
        requireEnabled();
        ParsedEmail p = parsedEmails.findWithMessageById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Parsed email", id));
        return mapper.toParsedEmailResponse(p, p.getRawJson());
    }

    /**
     * Who has told us about one cargo.
     *
     * <p>Deliberately not behind the parser switch. A cargo's sources are part of the cargo
     * and are shown on its own drawer; a deployment that has turned the parser off still has
     * whatever rows were written while it was on, and hiding them would make a merged cargo
     * look like one somebody typed.
     */
    @Transactional(readOnly = true)
    public List<CargoSourceResponse> cargoSources(Long cargoId) {
        return cargoSources.findByCargoIdOrderByReportedAtDesc(cargoId).stream()
                .map(mapper::toCargoSourceResponse)
                .toList();
    }

    // ------------------------------------------------------------------ internals

    IntakeItem load(Long id) {
        return items.findWithEmailById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Intake item", id));
    }

    private IntakeItemResponse toResponse(IntakeItem item, VesselLookupResponse lookup) {
        JsonNode payload;
        try {
            payload = json.readTree(item.getPayload());
        } catch (Exception e) {
            // One unreadable item must not take the page down with it. The row still lists,
            // with its label and its email, and the drawer says it cannot be applied.
            log.warn("Intake item {} has an unreadable payload: {}", item.getId(), e.getMessage());
            payload = null;
        }
        return mapper.toIntakeItemResponse(item, payload, summarise(item, payload), lookup);
    }

    /**
     * The one line the list row prints under the name.
     *
     * <p>Built from the payload rather than stored, because it is a rendering of it and a
     * stored copy is a second version to keep in step. Built on the server rather than in the
     * browser because the phone card and the table must not word it differently.
     */
    private static String summarise(IntakeItem item, JsonNode payload) {
        if (payload == null) return "This item cannot be read by this version.";
        return switch (item.getKind()) {
            case NEW_VESSEL -> {
                int suggestions = payload.path("suggestions").size();
                yield suggestions == 0
                        ? "Not on file. No similar vessels found."
                        : "Not on file. " + suggestions + " similar vessel(s) to check.";
            }
            case VESSEL_FIELDS -> {
                int diffs = payload.path("diffs").size();
                int filled = payload.path("filled").size();
                String head = diffs + (diffs == 1 ? " field differs" : " fields differ");
                if (filled > 0) head += "; " + filled + " empty field(s) already filled";
                // How she was identified belongs on the row, not only in the drawer: a
                // former-name match is the one worth opening first, and a queue that reads
                // the same for all three gives no reason to open any particular one.
                String matched = matchLabel(payload.path("matchedBy").asText(null));
                yield matched == null ? head : matched + " · " + head;
            }
            case CARGO_MERGE -> {
                JsonNode reasons = payload.path("reasons");
                List<String> parts = new ArrayList<>();
                reasons.forEach(r -> parts.add(r.asText()));
                yield parts.isEmpty() ? "Looks like a cargo already in hand."
                        : String.join("; ", parts);
            }
        };
    }

    /**
     * The match code as a phrase, or null when there is nothing to say.
     *
     * <p>Anything unrecognised comes back as it stands rather than as null: items raised
     * before the payload carried a code hold an English sentence, and printing it is better
     * than dropping the one fact the row most needs.
     */
    private static String matchLabel(String code) {
        if (code == null || code.isBlank()) return null;
        return switch (code) {
            case "IMO" -> "Matched by IMO";
            case "NAME" -> "Matched by name";
            case "EX_NAME" -> "Matched by a former name";
            default -> code;
        };
    }

    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new FeatureDisabledException(
                    "The email parser is not enabled on this deployment (PARSER_ENABLED).");
        }
    }
}
