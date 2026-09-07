package com.chartering.service.lookup;

import com.chartering.config.VesselLookupProperties;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.IntakeItem;
import com.chartering.model.IntakeItemKind;
import com.chartering.model.Vessel;
import com.chartering.model.VesselLookup;
import com.chartering.repository.IntakeItemRepository;
import com.chartering.repository.VesselLookupRepository;
import com.chartering.repository.VesselRepository;
import com.chartering.service.parser.Extraction;
import com.chartering.service.parser.IntakeResolver;
import com.chartering.service.parser.IntakePayloads;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finding a hull on an outside source, for the questions sitting in the review queue.
 *
 * <h2>What triggers it</h2>
 * <p><b>A hull is looked up because somebody has to answer a question about her</b>, not
 * because an email mentioned her. That is the whole cost control: a circular naming eighty
 * ships produces a handful of review items, and only those are worth somebody else's
 * bandwidth. A pass runs on a timer, takes the oldest few items with no lookup yet, and stops.
 * The drawer also has a button, for the item you are looking at right now.
 *
 * <p>Only where it could help: a hull with no IMO on either side, or one nothing on file
 * answers to at all. A vessel whose record and whose email agree on an IMO has nothing to
 * gain from a search and does not get one on the pass's own initiative — the drawer's button
 * searches for her anyway, because a person asking is not unprompted traffic.
 *
 * <p><b>An item the pass will not search for still gets a row.</b> The queue is "pending
 * items with no lookup row", so an item that produced none stayed at the head of it and was
 * reconsidered by every pass for ever, spending the allowance on hulls that were never going
 * to be searched for while the ones with no IMO queued behind them went unreached. See
 * {@link #skip}.
 *
 * <h2>Nothing here writes to a vessel</h2>
 * <p>Everything this produces is a proposal. A person accepts it field by field through
 * {@link #applyToVessel}, which is a separate action with its own change-set name, so the
 * History tab can say which figures came off the web and which came out of the mail. Folding
 * the two into one accept would save a click and lose exactly the provenance the feature
 * exists to provide.
 *
 * <h2>Manners</h2>
 * <p>One request at a time, process-wide, with a configured gap between them. This reads
 * somebody else's server on terms they did not offer; a burst is both rude and the surest way
 * to have the address blocked, which would end the feature for this desk entirely.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VesselLookupService {

    /** How often the pass looks for work. Cheap: it is one indexed query when idle. */
    private static final long TICK_MS = 120_000;

    /**
     * How many items deep a pass will look to find {@code maxPerPass} worth searching for.
     *
     * <p>Multiplier rather than a number, so a deployment that raises the cap gets a window
     * that grows with it. It exists because the queue holds two kinds of item and only one
     * of them is a question a search can answer.
     */
    private static final int SKIP_WINDOW = 5;

    private final VesselLookupProperties props;
    private final VesselLookupProvider provider;
    private final VesselLookupRepository lookups;
    private final IntakeItemRepository items;
    private final VesselRepository vessels;
    private final ObjectMapper json;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Guards the gap between outside requests. Process-wide, hence the single lock object. */
    private final Object requestLock = new Object();
    private volatile long lastRequestAt = 0L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vessel-lookup");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------ triggers

    /**
     * The unattended pass: look up whatever landed in the queue since last time.
     *
     * <p>Runs on the worker rather than the scheduler thread, so a slow or hanging source
     * cannot block whatever else Spring schedules.
     */
    @Scheduled(fixedDelay = TICK_MS, initialDelay = TICK_MS)
    public void enrichPending() {
        if (!props.isEnabled()) return;
        if (running.get()) return;
        worker.submit(this::runPass);
    }

    private void runPass() {
        if (!running.compareAndSet(false, true)) return;
        try {
            // Deliberately more items than the cap allows lookups. Most of what waits in
            // this queue cannot be helped by a search at all - a hull both the email and the
            // record name by IMO is the ordinary case - and asking for exactly `maxPerPass`
            // rows meant a pass could spend its whole allowance recognising items it was
            // going to skip, over and over. Those cost a payload read each and are answered
            // below with a row, so they leave the queue; the window is what stops the first
            // pass after a sweep from being the one that only clears them.
            List<Long> queue = lookups.pendingWithoutLookup(
                    PageRequest.of(0, props.getMaxPerPass() * SKIP_WINDOW));
            int searched = 0;
            for (Long itemId : queue) {
                if (searched >= props.getMaxPerPass()) break;
                try {
                    VesselLookup row = lookUp(itemId, false);
                    // A skip made no outside request, so it does not spend the allowance.
                    if (row != null && !VesselLookup.STATUS_SKIPPED.equals(row.getStatus())) {
                        searched++;
                    }
                } catch (Exception e) {
                    // One bad item must not end the pass; the row records what happened.
                    log.warn("Lookup for intake item {} failed: {}", itemId, e.toString());
                }
            }
        } catch (Exception e) {
            // Nothing above this catches: a dead worker thread would stop lookups for the
            // life of the process, silently.
            log.error("Vessel lookup pass failed", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * The button. Runs one lookup now and returns it, replacing whatever was there before.
     *
     * <p>Synchronous, unlike the parser's sweep: this is one HTTP request against one page
     * and the person pressing it is looking at the drawer waiting for the answer.
     *
     * <p><b>It searches for hulls the unattended pass would not.</b> That pass leaves an
     * already-identified ship alone because it is spending somebody else's bandwidth on its
     * own initiative; a person pressing this button has decided the request is worth making,
     * and a second opinion on a deadweight is a real reason to want one. The whole cost
     * argument is about unprompted traffic, and this is not that.
     */
    public VesselLookup lookUpNow(Long itemId) {
        requireEnabled();
        lookups.findByIntakeItemId(itemId).ifPresent(lookups::delete);
        return lookUp(itemId, true);
    }

    // ------------------------------------------------------------------ the lookup

    /**
     * Look one item's hull up, and record what came back.
     *
     * <p>Deliberately not {@code @Transactional}. The HTTP request is seconds of somebody
     * else's server and must not be made with a database transaction open around it; the
     * reads and the single write on either side are each atomic on their own, which is all
     * this needs.
     */
    public VesselLookup lookUp(Long itemId, boolean force) {
        IntakeItem item = items.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Intake item", itemId));

        Known known = knownFacts(item);
        // Nothing to search for at all, or nothing to be gained by searching. Recorded either
        // way; see skip() for why an unrecorded non-answer was starving the pass.
        if (known == null || (known.alreadyIdentified() && !force)) return skip(item);

        VesselLookup row = new VesselLookup();
        row.setIntakeItem(items.getReferenceById(itemId));
        row.setProvider(provider.name());
        row.setFetchedAt(OffsetDateTime.now());

        // At most two name forms, and the second only because the first found nothing. The
        // source matches the name as a literal substring, so "HACI HILMI II" misses a hull
        // held as "HACI HILMI-II" while "HACI HILMI" finds her - a punctuation mark between
        // the way two databases write one name, and the whole difference between an answer
        // and "nothing came back". See VesselNameQuery for why the loosening stops there.
        //
        // Each attempt goes through the throttle separately: a fallback is another request
        // against somebody else's server and must be paced like the first, not smuggled
        // inside one turn of the gate.
        List<String> forms = VesselNameQuery.forms(known.searchName());
        String imo = known.facts().imo();
        row.setQuery(imo != null ? imo : forms.isEmpty() ? known.searchName() : forms.get(0));

        List<VesselParticulars> candidates = List.of();
        try {
            // The number first wherever there is one, because it is the only question with a
            // single answer. A name is neither unique nor stable: searching by it for a hull
            // whose IMO we hold means asking an ambiguous question and then reasoning about
            // the ambiguity, which is how a renamed ship comes back as "no such ship" and a
            // common name comes back as three of them. The provider returns only hulls
            // actually carrying the number, so one row here is an identification and not a
            // resemblance.
            if (imo != null) {
                candidates = throttled(() -> provider.searchByImo(imo));
            }
            // Her number is not in that database, or we never had one. Fall back to the name
            // and let the matcher do the harder work.
            for (String form : forms) {
                if (!candidates.isEmpty()) break;
                candidates = throttled(() -> provider.searchByName(form));
                // What was actually sent, not what was meant - the drawer prints it.
                if (!candidates.isEmpty()) row.setQuery(form);
            }
        } catch (VesselLookupProvider.LookupException e) {
            row.setStatus(VesselLookup.STATUS_FAILED);
            row.setError(e.getMessage());
            return lookups.save(row);
        }

        // Ranked before it is trimmed. The page comes back in the source's own order, which
        // knows only the name it was asked for; keeping the first few would drop the hull
        // whose deadweight and build year agree because she was twelfth on a page of twenty.
        // The whole list is what the matcher judges - the "more than one ship answers to this
        // name" test is only as honest as the list it counts.
        List<LookupMatcher.Scored> ranked = LookupMatcher.score(known.facts(), candidates);
        row.setCandidatesJson(write(ranked.stream()
                .limit(props.getMaxCandidates())
                .map(LookupMatcher.Scored::candidate)
                .toList()));

        Optional<LookupMatcher.Scored> best =
                LookupMatcher.best(known.facts(), candidates, props.getMinConfidence());

        if (best.isEmpty()) {
            // Recorded rather than left blank: "we looked and could not tell" is an answer,
            // and it is what stops the same hull being searched for again every pass.
            row.setStatus(VesselLookup.STATUS_NO_MATCH);
            return lookups.save(row);
        }

        LookupMatcher.Scored scored = best.get();
        row.setStatus(VesselLookup.STATUS_OK);
        row.setMatchedImo(scored.candidate().imo());
        row.setConfidence(scored.confidence());
        row.setSourceUrl(scored.candidate().sourceUrl());
        return lookups.save(row);
    }

    /**
     * An item a search cannot help, recorded as that.
     *
     * <p><b>Written rather than left blank, for the same reason {@code NO_MATCH} is.</b> The
     * queue is "pending items with no lookup row", so an item that produced no row stayed in
     * it - and was picked up again by every pass, for ever. Most of what waits here is of
     * exactly that kind: a hull the email and the record both name by IMO has nothing to gain
     * from a search, and there are usually several of them at the head of the queue. They
     * filled the pass's allowance every two minutes and the hulls behind them - the ones with
     * no IMO, which are the only reason this feature exists - were never reached at all.
     *
     * <p>It costs no outside request and the drawer says which of the two kinds of silence
     * this is: "a search could only agree" is a different answer from "nothing came back".
     */
    private VesselLookup skip(IntakeItem item) {
        VesselLookup row = new VesselLookup();
        row.setIntakeItem(item);
        row.setProvider(provider.name());
        // The column is NOT NULL and nothing was searched for; the hull's own label is the
        // honest thing to put there, since it is what a search would have asked about.
        String label = item.getSubjectLabel();
        row.setQuery(label == null || label.isBlank() ? "—" : label);
        row.setStatus(VesselLookup.STATUS_SKIPPED);
        row.setFetchedAt(OffsetDateTime.now());
        return lookups.save(row);
    }

    /**
     * What is known about the hull an item is asking about, and what to search for.
     *
     * <p>Null when a search could not help — which is most items, and the caller records that
     * as a skip rather than as nothing at all; see {@link #skip}. The record's own figures are
     * preferred over the email's where both exist: the email is a broker's typing and the
     * record is what this desk has already checked, so the record is the better thing to
     * match a third source against.
     *
     * <p><b>The name is cleaned here and nowhere else</b>, so the string that is searched for
     * and the string a candidate is scored against are the same one. {@link LookupMatcher}
     * squashes punctuation but knows nothing of {@code MV}: score {@code MV HACI HILMI-II}
     * against the {@code HACI HILMI-II} the search returned and the name reads as a
     * disagreement — a search that worked, reported as the wrong ship.
     *
     * <p><b>Public because the screen has to score against exactly these facts.</b> The
     * confidence stored on the row was earned against them; if the drawer re-scored against a
     * different set it would print weaker evidence than the decision was made on — which it
     * did, showing "name only, uncorroborated" beside a confidence of 63 that had come from
     * the build year agreeing. One source of truth, for the same reason {@code ResolvedCargo}
     * exists.
     */
    public Known knownFacts(IntakeItem item) {
        if (item.getKind() == IntakeItemKind.NEW_VESSEL) {
            IntakePayloads.NewVessel payload = read(item, IntakePayloads.NewVessel.class);
            if (payload == null || payload.vessel() == null) return null;
            Extraction.ExtractedVessel v = payload.vessel();
            String name = VesselNameQuery.clean(Extraction.text(v.name()));
            if (name == null) return null;
            // The email's own number, where it gave one. It matched no hull here - that is
            // why this item exists - and the source can still say whose it is, which is the
            // rename case: she is on file under the name she carried three owners ago.
            String imo = IntakeResolver.normaliseImo(v.imo());
            // Nothing on file answers to her at all, so there is always something to gain.
            return new Known(name, new LookupMatcher.Known(
                    imo, name, v.built(), v.dwt(), Extraction.text(v.flag())), false);
        }

        if (item.getKind() != IntakeItemKind.VESSEL_FIELDS) return null;
        IntakePayloads.VesselFields payload = read(item, IntakePayloads.VesselFields.class);
        if (payload == null || payload.vesselId() == null) return null;

        Vessel vessel = vessels.findById(payload.vesselId()).orElse(null);
        if (vessel == null) return null;

        Extraction.ExtractedVessel parsed = payload.vessel();
        boolean emailHasImo = parsed != null && Extraction.text(parsed.imo()) != null;
        boolean recordHasImo = vessel.getImoNumber() != null && !vessel.getImoNumber().isBlank();

        String name = VesselNameQuery.clean(vessel.getName());
        if (name == null || name.isBlank()) return null;

        // The record's number ahead of the email's, and the order is not a preference about
        // whose typing is better - it is about which hull is being described. Everything this
        // lookup produces is written onto the vessel on file, so the search has to be about
        // her. The email's number is used only where the record has none, which is the case
        // this whole feature exists for; and where the two disagree, that disagreement is a
        // row in the table above and a question for a person, not something to resolve by
        // quietly asking the web about the other one.
        String imo = recordHasImo ? vessel.getImoNumber().trim()
                : parsed != null ? IntakeResolver.normaliseImo(parsed.imo()) : null;

        return new Known(name, new LookupMatcher.Known(
                imo,
                name,
                vessel.getYearBuilt() != null ? vessel.getYearBuilt()
                        : parsed != null ? parsed.built() : null,
                nonZero(vessel.getDeadweightTonnage()) != null ? vessel.getDeadweightTonnage()
                        : parsed != null ? parsed.dwt() : null,
                vessel.getFlag() != null ? vessel.getFlag()
                        : parsed != null ? Extraction.text(parsed.flag()) : null),
                emailHasImo && recordHasImo);
    }

    /**
     * The name to search for, the facts a candidate is scored against, and whether a search
     * would be telling us anything.
     *
     * @param alreadyIdentified both the email and the record name her by IMO. <b>A reason not
     *                          to search on the pass's own initiative, not an absence of
     *                          facts</b> — which is why it is a flag here rather than a null.
     *                          The drawer scores the candidate against exactly these facts
     *                          whatever prompted the lookup, and a forced search that fell
     *                          back to a thinner set would print weaker evidence than the
     *                          stored confidence was earned on
     */
    public record Known(String searchName, LookupMatcher.Known facts,
                        boolean alreadyIdentified) {
    }

    /**
     * One outside request at a time, no faster than the configured gap.
     *
     * <p>Blocking rather than dropping: the caller is a background pass or somebody who
     * pressed a button, and both would rather wait three seconds than be told to try again.
     */
    private <T> T throttled(java.util.function.Supplier<T> call) {
        synchronized (requestLock) {
            long wait = props.getMinRequestIntervalMs() - (System.currentTimeMillis() - lastRequestAt);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new VesselLookupProvider.LookupException("The lookup was interrupted.");
                }
            }
            try {
                return call.get();
            } finally {
                lastRequestAt = System.currentTimeMillis();
            }
        }
    }

    // ------------------------------------------------------------------ reading back

    public Optional<VesselLookup> forItem(Long itemId) {
        return lookups.findByIntakeItemId(itemId);
    }

    /** The candidates as they were read, for the review screen. Empty when none were stored. */
    public List<VesselParticulars> candidates(VesselLookup row) {
        if (row == null || row.getCandidatesJson() == null) return List.of();
        try {
            return json.readValue(row.getCandidatesJson(),
                    json.getTypeFactory().constructCollectionType(List.class, VesselParticulars.class));
        } catch (Exception e) {
            log.warn("Lookup {} has an unreadable candidate list: {}", row.getId(), e.getMessage());
            return List.of();
        }
    }

    /** The candidate the matcher preferred, by the IMO recorded against the row. */
    public Optional<VesselParticulars> matched(VesselLookup row) {
        if (row == null || row.getMatchedImo() == null) return Optional.empty();
        return candidates(row).stream()
                .filter(c -> row.getMatchedImo().equals(c.imo()))
                .findFirst();
    }

    /**
     * A hull already on file carrying the IMO this search turned up.
     *
     * <p>The best thing this feature does, and it falls out of the search for free. A
     * position list names a ship nothing answers to; the search supplies her IMO; that IMO is
     * already on a vessel here under the name she carried three owners ago. Without it she is
     * entered a second time and the fleet quietly doubles — which is the failure
     * {@code vessel_ex_names} exists to prevent and cannot, because nobody knew the two names
     * belonged together.
     */
    public Optional<Vessel> alreadyOnFile(VesselLookup row) {
        if (row == null || row.getMatchedImo() == null) return Optional.empty();
        List<Vessel> found = vessels.findByImoNumber(row.getMatchedImo());
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * Write the ticked fields onto a hull, as a change set that names where they came from.
     *
     * <p><b>Its own action, not part of accepting the email's figures</b>, and that separation
     * is the provenance. Each write is one transaction with one {@code ChangeContext}, so the
     * vessel's History tab reads "DWT 0 → 6,977 · Web lookup (vesselfinder) IMO 9014561" and
     * a figure can be traced to the search that produced it months later. Folded into the
     * email accept it would save a click and lose exactly the thing the feature is for.
     */
    @org.springframework.transaction.annotation.Transactional
    public List<String> applyToVessel(Long vesselId, VesselLookup row, List<String> fields) {
        requireEnabled();
        VesselParticulars candidate = matched(row)
                .orElseThrow(() -> new IllegalArgumentException(
                        "This lookup has no matched vessel to take values from."));
        Vessel vessel = vessels.findById(vesselId)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", vesselId));

        // An IMO already on another hull is not a constraint to work around - it is the
        // strongest evidence this feature produces, and it means the two records are one
        // ship. Caught here so it reads as that rather than as a unique-index violation:
        // ux_vessels_imo would otherwise fail the write with a 500 and no explanation.
        if (fields.contains("imoNumber") && candidate.imo() != null) {
            for (Vessel other : vessels.findByImoNumber(candidate.imo())) {
                if (!other.getId().equals(vesselId)) {
                    throw new IllegalArgumentException(
                            "IMO %s is already on %s (#%d). That is the same hull under "
                                    .formatted(candidate.imo(), other.getName(), other.getId())
                            + "another name — link the position to her rather than writing "
                            + "the number onto a second record. Untick IMO to take the other "
                            + "figures.");
                }
            }
        }

        com.chartering.audit.ChangeContext.describe("Web lookup (%s) IMO %s — %s"
                .formatted(row.getProvider(), candidate.imo(), row.getSourceUrl()));

        List<String> written = LookupFields.apply(vessel, candidate, fields);
        if (!written.isEmpty()) {
            // Ties the hull back to the search, so the row is findable from either end.
            row.setVesselId(vesselId);
            lookups.save(row);
        }
        return written;
    }

    // ------------------------------------------------------------------ internals

    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new FeatureDisabledException(
                    "Vessel lookup is not enabled on this deployment (LOOKUP_ENABLED).");
        }
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    public String providerName() {
        return provider.name();
    }

    /** Zero means "not on file" on the older rows here — see {@code VesselFieldDiff}. */
    private static BigDecimal nonZero(BigDecimal value) {
        return value == null || value.signum() == 0 ? null : value;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T read(IntakeItem item, Class<T> type) {
        try {
            return json.readValue(item.getPayload(), type);
        } catch (Exception e) {
            log.warn("Intake item {} has an unreadable payload: {}", item.getId(), e.getMessage());
            return null;
        }
    }
}
