package com.chartering.service.parser;

import com.chartering.audit.ChangeContext;
import com.chartering.model.*;
import com.chartering.repository.*;
import com.chartering.service.CargoService;
import com.chartering.service.VesselService;
import com.chartering.service.lookup.VesselLookupService;
import com.chartering.service.QuantityTolerance;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turning one read email into rows — and into the questions it could not answer.
 *
 * <p><b>The whole feature's judgement lives in one distinction.</b> A parse may write, on its
 * own, anything that only <em>adds</em>: a position for a hull already on file, a cargo
 * nothing else looks like, a particular filling a column that was empty. It may not write
 * anything that <em>changes</em> what a person put there: a deadweight that disagrees, a
 * hull nobody has heard of, a cargo that looks like one already in hand. Those three stop and
 * become {@link IntakeItem}s.
 *
 * <p>The line is drawn at what a wrong reading costs, not at how confident the model is. An
 * invented position is superseded by tomorrow's list and deleted in one click; an invented
 * deadweight sits in the vessel record looking like something a broker checked, and every
 * match run afterwards is quietly wrong. Confidence would have been the tempting rule and it
 * is the wrong one: the model is most confident on the circular formats it saw most of,
 * which is not the same as being right about this hull.
 *
 * <p><b>Nothing here is audited, and that is deliberate.</b> {@code VesselPosition} is
 * outside {@code AuditedEntities} for exactly the reason that applies here at scale — one
 * circular is eighty positions, and logging them would record a machine copying an email into
 * a table. What a person does on the Intake tab <em>is</em> audited, because accepting a
 * conflict writes to {@code Vessel} and {@code Cargo}, both of which are on the whitelist.
 * The change set is named, so a merge reads in the history as one event rather than as nine
 * unexplained field edits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntakeService {

    private final IntakeItemRepository items;
    private final CargoSourceRepository cargoSources;
    private final CargoRepository cargoes;
    private final VesselRepository vessels;
    private final VesselExNameRepository exNames;
    private final IntakeItemSourceRepository itemSources;
    private final VesselPositionRepository positions;
    private final IntakeResolver resolver;
    private final VesselLookupService lookups;
    private final VesselService vesselService;
    private final ObjectMapper json;

    /** What one email produced, for the parse row and the sweep's log line. */
    public record ApplyOutcome(int positionsApplied, int cargoesApplied, int itemsRaised) {
    }

    // ------------------------------------------------------------------ applying

    /**
     * Apply one reading.
     *
     * <p>Runs inside the sweep's transaction, so a parse either lands whole or not at all —
     * a circular that produced forty positions and then hit a constraint on the forty-first
     * must not leave forty orphans behind and no record of having been read.
     */
    @Transactional
    public ApplyOutcome apply(ParsedEmail parsed, Extraction extraction) {
        MailMessage message = parsed.getMailMessage();
        // Names the transaction's whole change set, so the gap fills a circular writes read
        // in the History tab as one event with a cause rather than as scattered edits. The
        // importer uses it the same way for the same reason.
        ChangeContext.describe("Read from mail: " + subjectOf(message));

        int positionsApplied = 0;
        int cargoesApplied = 0;
        int itemsRaised = 0;

        for (Extraction.ExtractedVessel v : extraction.vesselsOrEmpty()) {
            if (!v.isUsable()) continue;
            VesselOutcome outcome = applyVessel(parsed, message, v);
            positionsApplied += outcome.positionApplied() ? 1 : 0;
            itemsRaised += outcome.itemsRaised();
        }

        for (Extraction.ExtractedCargo c : extraction.cargoesOrEmpty()) {
            if (!c.isUsable()) continue;
            CargoOutcome outcome = applyCargo(parsed, message, c);
            cargoesApplied += outcome.cargoApplied() ? 1 : 0;
            itemsRaised += outcome.itemsRaised();
        }

        return new ApplyOutcome(positionsApplied, cargoesApplied, itemsRaised);
    }

    private record VesselOutcome(boolean positionApplied, int itemsRaised) {
    }

    private record CargoOutcome(boolean cargoApplied, int itemsRaised) {
    }

    /**
     * One vessel paragraph: her position, and whatever it says about her.
     *
     * <p>Matched by IMO, then by name (current or former), then not at all — see
     * {@link IntakeResolver#resolveVessel}. On a match the position is filed immediately,
     * because where she is open is the perishable half and is what the desk is waiting for;
     * a disagreement about her particulars is raised alongside it and can wait, since a
     * deadweight that has been wrong since Tuesday is not more wrong by Thursday.
     */
    private VesselOutcome applyVessel(ParsedEmail parsed, MailMessage message,
                                      Extraction.ExtractedVessel v) {
        IntakeResolver.ResolvedVessel match = resolver.resolveVessel(v);
        if (!match.found()) {
            return new VesselOutcome(false, raiseNewVessel(parsed, v) ? 1 : 0);
        }

        Vessel vessel = match.vessel();
        VesselFieldDiff.Result diff = VesselFieldDiff.compare(vessel, v);
        int raised = 0;
        if (diff.hasConflicts() && raiseVesselFields(parsed, vessel, match.how(), v, diff)) {
            raised = 1;
        }
        boolean applied = recordPosition(vessel, v, message, parsed);
        return new VesselOutcome(applied, raised);
    }

    /**
     * File a position — or recognise that we have already been told this.
     *
     * <p><b>The re-confirmation rule.</b> A broker's list arrives every morning and most of
     * it is yesterday's list again. Inserting a row each time would be defensible — it is a
     * fresh statement — but it makes a hull's history a record of Mondays rather than of
     * openings, and "she has opened in the Adriatic five times this year" stops meaning
     * anything. So an identical reading from the same reporter, against a row still LIVE,
     * moves that row's {@code reportedAt} forward instead of twinning it: nothing about the
     * position changed, only when we last heard it, which is precisely what that column
     * means to Open Fleet's staleness. Anything that differs by so much as a date is a new
     * row and supersedes the reporter's previous one, which is the existing rule and is left
     * alone.
     *
     * <p>Superseding is scoped to the same reporter, here as everywhere: when GN says she is
     * open Adriatic and Interscan says the Aegean, both are what we were told.
     */
    private boolean recordPosition(Vessel vessel, Extraction.ExtractedVessel v,
                                   MailMessage message, ParsedEmail parsed) {
        Port openPort = resolver.resolvePort(v.openPort());
        TradeArea openArea = resolver.resolveArea(v.openArea(), v.openPort(), openPort);
        LocalDate openFrom = IntakeResolver.date(v.openFrom());
        LocalDate openTo = IntakeResolver.date(v.openTo());
        Company reporter = message.getCompany();
        OffsetDateTime reportedAt = reportedAt(message);

        List<VesselPosition> existing = positions.findByVesselIdOrderByReportedAtDesc(vessel.getId());
        Optional<VesselPosition> reconfirmed = existing.stream()
                .filter(p -> p.getStatus() == PositionStatus.LIVE)
                .filter(p -> sameReporter(p, reporter))
                .filter(p -> sameReading(p, openPort, openArea, openFrom, openTo, v))
                .findFirst();
        if (reconfirmed.isPresent()) {
            VesselPosition p = reconfirmed.get();
            // Only ever forward. A circular read out of a week-old email must not drag a
            // fresher reading of the same position backwards and make it look stale.
            if (p.getReportedAt() == null || p.getReportedAt().isBefore(reportedAt)) {
                p.setReportedAt(reportedAt);
                p.setSourceMailMessage(message);
                p.setFromMail(true);
            }
            return false;
        }

        for (VesselPosition p : existing) {
            if (p.getStatus() == PositionStatus.LIVE && sameReporter(p, reporter)) {
                p.setStatus(PositionStatus.SUPERSEDED);
            }
        }

        VesselPosition p = new VesselPosition();
        p.setVessel(vessel);
        p.setStatus(PositionStatus.LIVE);
        p.setOpenPort(openPort);
        p.setOpenPortText(Extraction.text(v.openPort()));
        p.setOpenArea(openArea);
        p.setOpenFrom(openFrom);
        p.setOpenTo(openTo);
        p.setOpenText(Extraction.text(v.openText()));
        p.setLastCargo(Extraction.text(v.lastCargo()));
        p.setCargoPreferences(Extraction.text(v.cargoPreferences()));
        p.setReportedByCompany(reporter);
        p.setReportedByPerson(message.getPerson());
        p.setSourceMailMessage(message);
        p.setFromMail(true);
        p.setReportedAt(reportedAt);
        p.setNotes(Extraction.text(v.notes()));
        positions.save(p);
        return true;
    }

    /**
     * One cargo: created outright, or proposed as a merge into one already in hand.
     *
     * <p>Every cargo that lands — merged or new — gets a {@link CargoSource} row, which is
     * what keeps "who else is working this" answerable after a merge has folded three emails
     * into one record.
     */
    private CargoOutcome applyCargo(ParsedEmail parsed, MailMessage message,
                                    Extraction.ExtractedCargo c) {
        ResolvedCargo resolved = resolve(c);

        List<Cargo> candidates = cargoes.findLive(CargoService.LIVE_STATUSES);
        Optional<CargoMatcher.Candidate> duplicate =
                CargoMatcher.findDuplicate(c, candidates, resolved.loadPort(), resolved.loadArea());

        if (duplicate.isPresent()) {
            return new CargoOutcome(false, raiseCargoMerge(parsed, resolved, duplicate.get()) ? 1 : 0);
        }

        Cargo cargo = createCargo(resolved, message);
        addSource(cargo, message, null);
        return new CargoOutcome(true, 0);
    }

    // ------------------------------------------------------------------ raising items

    /**
     * Raise an item, unless the same question is already waiting.
     *
     * <p>Suppression is not tidiness, it is what keeps the queue readable: a disagreement
     * nobody has answered arrives again with every morning's list, and thirty copies of one
     * question is a queue that stops being opened. The incoming reading is compared against
     * the pending one, so a genuinely new disagreement still gets through — what is
     * suppressed is the same email said twice, not a second opinion.
     *
     * @return whether an item was actually created
     */
    private boolean raiseNewVessel(ParsedEmail parsed, Extraction.ExtractedVessel v) {
        String name = Extraction.text(v.name());
        if (!items.pendingNewVessel(name).isEmpty()) return false;

        String searchedBy = IntakeResolver.normaliseImo(v.imo()) != null
                ? "IMO " + IntakeResolver.normaliseImo(v.imo()) + " and the name \"" + name + "\""
                : "the name \"" + name + "\"";
        IntakePayloads.NewVessel payload =
                new IntakePayloads.NewVessel(v, searchedBy, resolver.suggest(v));
        addSource(save(parsed, IntakeItemKind.NEW_VESSEL, null, null, name, payload), parsed);
        return true;
    }

    /**
     * Raise the question about this hull — or add this email to the one already asking it.
     *
     * <p><b>One pending question per vessel, however many emails raise it.</b> The question is
     * about a ship rather than about an email: a broker re-sends his list on Monday and again
     * on Wednesday, two brokers carry the same hull, and every arrival used to produce its own
     * row. The queue then showed one vessel three times and answering one left the others
     * sitting there, still asking.
     *
     * <p>Exact suppression was the old answer and it was too brittle to be one. It compared the
     * two sets of disagreements literally, so FOX came back twice over a single reworded word —
     * "GENERAL-DRY CARGO VESSEL / DOUBLE SKIN/BOX" against "GENERAL-DRY CARGO VESSEL", every
     * other figure identical. Loosening the comparison would not have helped: those two strings
     * genuinely differ, and so will the next pair. The fix is not a better test for "the same
     * question" but recognising that it was always one question.
     *
     * <p><b>The newest reading wins the figures, and every arrival is kept.</b> Merging takes
     * the union by field with this email's value where both speak, because the later list is
     * the later statement and that is what a desk would act on. Nothing is lost by it: each
     * source stays readable in full from the item, which is where a reviewer settles a
     * disagreement between two brokers — by reading what each of them actually wrote, rather
     * than by trusting a column that picked one.
     *
     * <p>Only pending items merge. An answered question is history, and an email disagreeing
     * again afterwards is a new question about a record that has since been decided.
     */
    private boolean raiseVesselFields(ParsedEmail parsed, Vessel vessel,
                                      IntakeResolver.VesselMatch how,
                                      Extraction.ExtractedVessel v,
                                      VesselFieldDiff.Result diff) {
        for (IntakeItem pending : items.pendingForVessel(IntakeItemKind.VESSEL_FIELDS, vessel.getId())) {
            IntakePayloads.VesselFields existing =
                    read(pending, IntakePayloads.VesselFields.class);
            if (existing == null) continue;
            mergeInto(pending, existing, how, v, diff);
            addSource(pending, parsed);
            // Not a new question, so the sweep's "raised" count does not grow. The email is on
            // the item and the reviewer will see it; the queue is no longer than it was.
            return false;
        }
        IntakePayloads.VesselFields payload = new IntakePayloads.VesselFields(
                vessel.getId(), vessel.getName(), matchNote(how), v,
                diff.conflicts(), diff.filled());
        IntakeItem item = save(parsed, IntakeItemKind.VESSEL_FIELDS, vessel.getId(), null,
                vessel.getName(), payload);
        addSource(item, parsed);
        return true;
    }

    /**
     * Turn "is this a new ship?" into "her particulars disagree", wherever the number says so.
     *
     * <p><b>An IMO is identity.</b> Two records carrying one IMO are one hull, with no room
     * for judgement in it — so a {@code NEW_VESSEL} item whose lookup came back with a number
     * this database already holds is not a question about whether to create a ship. It is a
     * question about a ship we have, under a name nobody here recognised, which is exactly
     * what a {@code VESSEL_FIELDS} item is for. The screen used to say so in a paragraph and
     * then leave the reader to close the drawer and go and link her by hand; seventeen of the
     * forty-one hulls waiting in this queue were that.
     *
     * <p><b>What converting does and does not decide.</b> It changes which question is asked,
     * not what is written to her: every field still waits for a person, and the rename reads
     * as an ordinary row in the table — "Name: CELIA → LIUDMILA" — which is the honest shape
     * of it. The position is filed on her, because that is an <em>add</em> and the perishable
     * half the desk is waiting for; if the identification were wrong, tomorrow's list
     * supersedes it, which is the whole reason positions are append-only.
     *
     * <p>The former name is deliberately not filed here. That is a claim about identity which
     * outlives this item and steers every future match, and it is written by accepting the
     * name row — a person's decision, on the screen that shows them both names.
     *
     * <p>{@code LOOKUP_IMO} says where the identification came from, so the drawer can show
     * the lookup's own confidence beside it. The IMO-to-hull step is certain; whether the
     * source was talking about this email's ship is the part worth a reader's eye, and a
     * name-only match says so on the card.
     */
    @Transactional
    public int reconcileIdentifiedHulls() {
        int converted = 0;
        for (IntakeItem item : items.pendingByKind(IntakeItemKind.NEW_VESSEL)) {
            try {
                if (convertToFieldsReview(item)) converted++;
            } catch (Exception e) {
                // One item must not stop the rest: this runs unattended after every pass.
                log.warn("Could not reconcile intake item {}: {}", item.getId(), e.toString());
            }
        }
        return converted;
    }

    private boolean convertToFieldsReview(IntakeItem item) {
        VesselLookup row = lookups.forItem(item.getId()).orElse(null);
        if (row == null || !VesselLookup.STATUS_OK.equals(row.getStatus())) return false;
        Vessel vessel = lookups.alreadyOnFile(row).orElse(null);
        if (vessel == null) return false;

        IntakePayloads.NewVessel payload = read(item, IntakePayloads.NewVessel.class);
        if (payload == null || payload.vessel() == null) return false;

        VesselFieldDiff.Result diff = VesselFieldDiff.compare(vessel, payload.vessel());
        item.setKind(IntakeItemKind.VESSEL_FIELDS);
        item.setVesselId(vessel.getId());
        item.setSubjectLabel(truncate(vessel.getName()));
        item.setPayload(write(new IntakePayloads.VesselFields(
                vessel.getId(), vessel.getName(),
                matchNote(IntakeResolver.VesselMatch.LOOKUP_IMO), payload.vessel(),
                diff.conflicts(), diff.filled())));
        items.save(item);

        // Her position, now that there is a hull to file it against. An add, and the half of
        // the email that goes stale — the particulars can wait for a reviewer, "where is she
        // open" cannot.
        ParsedEmail parsed = item.getParsedEmail();
        recordPosition(vessel, payload.vessel(), parsed.getMailMessage(), parsed);
        return true;
    }

    /**
     * Fold a fresh reading into the question already waiting.
     *
     * <p>Union by field with the newer email's value where the two speak about the same one.
     * The vessel paragraph is replaced wholesale for the same reason — accepting the item
     * writes from it, and a half-old half-new paragraph would write figures no single email
     * ever stated.
     */
    private void mergeInto(IntakeItem pending, IntakePayloads.VesselFields existing,
                           IntakeResolver.VesselMatch how, Extraction.ExtractedVessel v,
                           VesselFieldDiff.Result diff) {
        Map<String, FieldDiff> byField = new LinkedHashMap<>();
        for (FieldDiff d : existing.diffs()) byField.put(d.field(), d);
        for (FieldDiff d : diff.conflicts()) byField.put(d.field(), d);

        List<String> filled = new ArrayList<>(existing.filled() == null
                ? List.of() : existing.filled());
        for (String f : diff.filled()) if (!filled.contains(f)) filled.add(f);

        pending.setPayload(write(new IntakePayloads.VesselFields(
                existing.vesselId(), existing.vesselName(), matchNote(how), v,
                List.copyOf(byField.values()), List.copyOf(filled))));
        items.save(pending);
    }

    /**
     * Record that this email raised the item too.
     *
     * <p>Guarded because re-parsing a message must not double up its sources — the same rule
     * the unique index states, checked here so it reads as "already counted" rather than as a
     * constraint violation and a 500.
     */
    private void addSource(IntakeItem item, ParsedEmail parsed) {
        if (itemSources.existsByIntakeItemIdAndParsedEmailId(item.getId(), parsed.getId())) return;
        MailMessage message = parsed.getMailMessage();
        IntakeItemSource source = new IntakeItemSource();
        source.setIntakeItem(item);
        source.setParsedEmail(parsed);
        source.setMailMessage(message);
        source.setReportedByCompany(message == null ? null : message.getCompany());
        source.setReportedAt(reportedAt(message));
        itemSources.save(source);
    }

    private boolean raiseCargoMerge(ParsedEmail parsed, ResolvedCargo resolved,
                                    CargoMatcher.Candidate candidate) {
        Cargo existing = candidate.cargo();
        CargoFieldDiff.Result preview = CargoFieldDiff.merge(existing, resolved, false);
        IntakePayloads.CargoMerge payload = new IntakePayloads.CargoMerge(
                resolved.parsed(), existing.getId(), describe(existing),
                candidate.reasons(), preview.filled(), preview.differing());
        addSource(save(parsed, IntakeItemKind.CARGO_MERGE, null, existing.getId(),
                Extraction.text(resolved.parsed().commodity()), payload), parsed);
        return true;
    }

    private IntakeItem save(ParsedEmail parsed, IntakeItemKind kind, Long vesselId, Long cargoId,
                            String label, Object payload) {
        IntakeItem item = new IntakeItem();
        item.setParsedEmail(parsed);
        item.setKind(kind);
        item.setVesselId(vesselId);
        item.setCargoId(cargoId);
        item.setSubjectLabel(truncate(label));
        item.setPayload(write(payload));
        return items.save(item);
    }

    // ------------------------------------------------------------------ resolving items

    /**
     * What a person decided about one item.
     *
     * <p>Every kind has a "do it", a "do the other thing" and a "neither", because on two of
     * the three the second option also writes: keeping a cargo separate creates it, and
     * linking a new vessel to an existing hull files the position against that hull. Calling
     * either of those a rejection would be a lie in the history — the only real rejection is
     * DISCARD, which is what "the model read this wrong" looks like.
     */
    public enum Action {
        /** Create the vessel / write the ticked fields / merge into the candidate. */
        ACCEPT,
        /** Link the position to a vessel already on file, or keep the cargo as its own row. */
        ALTERNATIVE,
        /** Write nothing. The reading was wrong. */
        DISCARD
    }

    /** What resolving one item did, in the words the screen shows back. */
    public record Resolution(IntakeItem item, String summary) {
    }

    /**
     * Answer one item.
     *
     * <p>Loads it here rather than taking an entity, and it has to: {@code open-in-view} is
     * off, so an item fetched in a controller would arrive detached and every change made to
     * it would be dropped without a word.
     */
    @Transactional
    public Resolution resolve(Long id, Action action, List<String> fields,
                              Long vesselId, String note, String user) {
        IntakeItem item = items.findWithEmailById(id)
                .orElseThrow(() -> new com.chartering.exception.ResourceNotFoundException(
                        "Intake item", id));
        if (item.getStatus() != IntakeItemStatus.PENDING) {
            throw new IllegalArgumentException("This item has already been answered.");
        }
        ChangeContext.describe("Intake: " + item.getKind() + " " + action);

        String summary = switch (item.getKind()) {
            case NEW_VESSEL -> resolveNewVessel(item, action, vesselId);
            case VESSEL_FIELDS -> resolveVesselFields(item, action, fields);
            case CARGO_MERGE -> resolveCargoMerge(item, action);
        };

        item.setStatus(action == Action.DISCARD ? IntakeItemStatus.REJECTED : IntakeItemStatus.ACCEPTED);
        item.setResolvedAt(OffsetDateTime.now());
        item.setResolvedBy(user);
        item.setResolutionNote(note == null || note.isBlank() ? summary : note.strip());
        return new Resolution(item, summary);
    }

    private String resolveNewVessel(IntakeItem item, Action action, Long vesselId) {
        IntakePayloads.NewVessel payload = require(item, IntakePayloads.NewVessel.class);
        if (action == Action.DISCARD) return "Discarded; no vessel created.";

        MailMessage message = item.getParsedEmail().getMailMessage();
        Vessel vessel;
        String summary;
        if (action == Action.ALTERNATIVE) {
            if (vesselId == null) {
                throw new IllegalArgumentException(
                        "Choose the vessel this position belongs to, or create a new one.");
            }
            vessel = vessels.findById(vesselId).orElseThrow(() ->
                    new com.chartering.exception.ResourceNotFoundException("Vessel", vesselId));
            // The name the email used is a name this hull answers to, and it is the name a
            // later list will use again. Filing it now is what stops the next circular
            // raising the identical item - which is the whole reason ex-names exist.
            rememberExName(vessel, payload.vessel().name(), vessel.getName());
            VesselFieldDiff.Result diff = VesselFieldDiff.compare(vessel, payload.vessel());
            summary = "Linked to " + vessel.getName()
                    + (diff.hasConflicts()
                    ? "; " + diff.conflicts().size() + " field(s) differ and were left alone"
                    : "");
        } else {
            vessel = createVessel(payload.vessel());
            summary = "Created " + vessel.getName() + " and filed her position.";
        }

        recordPosition(vessel, payload.vessel(), message, item.getParsedEmail());
        item.setVesselId(vessel.getId());
        return summary;
    }

    private String resolveVesselFields(IntakeItem item, Action action, List<String> fields) {
        IntakePayloads.VesselFields payload = require(item, IntakePayloads.VesselFields.class);
        if (action == Action.DISCARD) {
            return "Kept what was on file; nothing changed.";
        }
        Vessel vessel = vessels.findById(payload.vesselId()).orElseThrow(() ->
                new com.chartering.exception.ResourceNotFoundException("Vessel", payload.vesselId()));

        if (action == Action.ALTERNATIVE) return separateVessel(item, payload, vessel);

        // An empty list means all of them, which is what the "Accept all" button sends. A
        // list that names nothing and meant nothing would be an accept that quietly did
        // nothing, so the UI never sends one.
        List<String> chosen = fields == null || fields.isEmpty()
                ? payload.diffs().stream().map(FieldDiff::field).toList()
                : fields;

        // A rename is the one accepted field that has a second effect: the name she is
        // losing is the name this database has been finding her under, and dropping it would
        // make every older position list unsearchable for her.
        if (chosen.contains("name")) {
            rememberExName(vessel, vessel.getName(),
                    payload.vessel() == null ? null : payload.vessel().name());
        }

        List<String> written = VesselFieldDiff.applySelected(vessel, payload.vessel(), chosen);
        if (written.isEmpty()) return "Nothing changed — the record already reads that way.";
        return "Updated " + String.join(", ", written.stream().map(VesselFieldDiff::labelOf).toList())
                + " on " + vessel.getName() + ".";
    }

    /**
     * She is not that ship: create the hull the email describes, and move the reading to her.
     *
     * <p><b>The answer this screen was missing.</b> Matching is exact - IMO, then current
     * name, then a former name - and exact is not the same as right: a name is re-used when
     * an owner scraps a ship and gives it to the next one, and a former-name hit is right for
     * a reason nobody can see from the row. The screen already shows the particulars side by
     * side precisely so a person can notice that they describe two different vessels, and
     * until now the only answers to noticing were to accept figures onto the wrong hull or to
     * discard the reading entirely. Both lose the ship the email was actually about.
     *
     * <p><b>What happens to the position already filed against the matched hull.</b> By the
     * time this item exists the parse has recorded her position there, because where a ship
     * is open is the perishable half and does not wait for a review. That reading was never
     * about that vessel, so it comes off Open Fleet - as {@code WITHDRAWN}, not deleted.
     * Positions are append-only and a hull's history is worth more than a tidy table; the
     * status says the reading was pulled, the row still says who reported what and when, and
     * "why did she show open Marmara last week" stays answerable.
     *
     * <p>Only what this email put there. A row an earlier circular created and this one
     * merely re-confirmed belongs to that earlier reading, and taking it down would be
     * correcting somebody else's record on the strength of this one.
     */
    private String separateVessel(IntakeItem item, IntakePayloads.VesselFields payload,
                                  Vessel matched) {
        MailMessage message = item.getParsedEmail().getMailMessage();

        int withdrawn = 0;
        for (VesselPosition p : positions.findByVesselIdOrderByReportedAtDesc(matched.getId())) {
            if (p.getStatus() == PositionStatus.LIVE
                    && p.getSourceMailMessage() != null
                    && p.getSourceMailMessage().getId().equals(message.getId())) {
                p.setStatus(PositionStatus.WITHDRAWN);
                withdrawn++;
            }
        }

        Vessel created = createVessel(payload.vessel());
        recordPosition(created, payload.vessel(), message, item.getParsedEmail());
        item.setVesselId(created.getId());

        return "Created " + created.getName() + " as a separate vessel and filed the position "
                + "on her" + (withdrawn > 0
                ? "; the reading this email put on " + matched.getName() + " was withdrawn."
                : ". Nothing was taken off " + matched.getName() + " - this email had added "
                  + "no live position there.");
    }

    private String resolveCargoMerge(IntakeItem item, Action action) {
        IntakePayloads.CargoMerge payload = require(item, IntakePayloads.CargoMerge.class);
        MailMessage message = item.getParsedEmail().getMailMessage();
        ResolvedCargo resolved = resolve(payload.cargo());

        if (action == Action.DISCARD) return "Discarded; no cargo written.";

        if (action == Action.ALTERNATIVE) {
            Cargo cargo = createCargo(resolved, message);
            addSource(cargo, message, "Kept separate from cargo #" + payload.candidateId());
            item.setCargoId(cargo.getId());
            return "Kept as a separate cargo.";
        }

        Cargo existing = cargoes.findById(payload.candidateId()).orElseThrow(() ->
                new com.chartering.exception.ResourceNotFoundException("Cargo", payload.candidateId()));
        CargoFieldDiff.Result merged = CargoFieldDiff.merge(existing, resolved, true);
        addSource(existing, message, null);
        return merged.filled().isEmpty()
                ? "Merged; the cargo already held everything this email said."
                : "Merged, filling " + merged.filled().size() + " empty field(s).";
    }

    // ------------------------------------------------------------------ the outside source

    /**
     * Write the ticked figures from a lookup onto the hull this item is about.
     *
     * <p>Its own call rather than part of {@link #resolve}, and that is the provenance: this
     * runs in its own transaction with its own change-set name, so the vessel's History tab
     * can say which values came off the web and which came out of the mail. One combined
     * accept would save a click and lose the distinction the whole feature exists to make.
     *
     * @param vesselId needed only on a {@code NEW_VESSEL} item, where no record exists until
     *                 the item has been accepted; ignored when the item already names a hull
     */
    @Transactional
    public List<String> applyLookup(Long itemId, List<String> fields, Long vesselId) {
        IntakeItem item = items.findWithEmailById(itemId).orElseThrow(() ->
                new com.chartering.exception.ResourceNotFoundException("Intake item", itemId));

        Long target = item.getVesselId() != null ? item.getVesselId() : vesselId;
        if (target == null) {
            throw new IllegalArgumentException(
                    "There is no vessel to write to yet — create her or link her to one on "
                            + "file first, then take the figures from the lookup.");
        }
        VesselLookup row = lookups.forItem(itemId).orElseThrow(() ->
                new IllegalArgumentException("Nothing has been looked up for this item."));

        List<String> written = lookups.applyToVessel(target, row, fields);
        if (item.getVesselId() == null) item.setVesselId(target);
        return written;
    }

    /**
     * Attach the company that sent the email to the hull it was about.
     *
     * <p>The ordinary case is a position list from a broker who is not the owner on file, and
     * that the broker works this hull is worth keeping — it is who to ring about her. The
     * capacity is the caller's to choose rather than assumed: {@code owner} displaces the
     * owner on the record, the two broker roles sit alongside it, and getting that wrong
     * would quietly reassign a ship.
     *
     * <p>Delegates to the vessel's own rule so a company appears once per hull, and so the
     * Intake tab and the vessel screen cannot drift into two different notions of what a link
     * is.
     */
    @Transactional
    public void linkSenderCompany(Long itemId, String role, Long companyId, String notes) {
        IntakeItem item = items.findWithEmailById(itemId).orElseThrow(() ->
                new com.chartering.exception.ResourceNotFoundException("Intake item", itemId));
        if (item.getVesselId() == null) {
            throw new IllegalArgumentException(
                    "There is no vessel to attach a company to yet — create her or link her to "
                            + "one on file first.");
        }
        Company sender = chooseSender(item, itemId, companyId);
        if (sender == null) {
            throw new IllegalArgumentException(
                    "The sender of that email is not linked to a company. Link it on the "
                            + "Mailbox tab first, and the address will be recognised from then on.");
        }
        ChangeContext.describe("Intake: linked %s to the vessel as %s"
                .formatted(sender.getName(), role));
        vesselService.setLink(item.getVesselId(), sender.getId(), role, notes);
    }

    /**
     * Which of the item's senders to attach.
     *
     * <p>Checked against the item's own arrivals rather than taken on trust. This endpoint
     * records what an email is evidence of — that this firm was writing about this hull — and
     * a company id that appears on none of them is not that. Attaching an unrelated firm is a
     * decision about the ship and belongs on her own record, where every link is in view.
     */
    private Company chooseSender(IntakeItem item, Long itemId, Long companyId) {
        if (companyId == null) {
            MailMessage message = item.getParsedEmail().getMailMessage();
            return message == null ? null : message.getCompany();
        }
        return itemSources.forItem(itemId).stream()
                .map(IntakeItemSource::getReportedByCompany)
                .filter(Objects::nonNull)
                .filter(c -> c.getId().equals(companyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "That company did not send any of the emails behind this item. Attach it "
                                + "on the vessel's own record instead."));
    }

    // ------------------------------------------------------------------ writing rows

    /**
     * A vessel out of a circular.
     *
     * <p>Built by gap-filling a blank record, which writes every particular the email gave
     * and nothing it did not — the same pass the matched case uses, so a created hull and an
     * updated one cannot end up reading a field two different ways.
     *
     * <p>Not confirmed and not legacy. {@code confirmed} means somebody reached the owner and
     * checked; {@code legacy} means carried over from the old database. A ship read out of an
     * email this morning is neither, and claiming either would put her in filters she has no
     * business being in.
     */
    private Vessel createVessel(Extraction.ExtractedVessel v) {
        Vessel vessel = new Vessel();
        vessel.setName(Extraction.text(v.name()));
        VesselFieldDiff.compare(vessel, v);
        vessel.setNotes(Extraction.text(v.notes()));
        return vessels.save(vessel);
    }

    /**
     * File a name she used to carry.
     *
     * <p><b>The name she is keeping has to be passed in, and that is the whole of the bug this
     * signature exists to prevent.</b> There are two callers and they mean opposite things by
     * "her name". Linking a position to a hull on file records the name the <em>email</em>
     * used, and the thing not worth recording is a name identical to the one she already has.
     * Accepting a rename records the name she <em>is losing</em>, and the thing not worth
     * recording is a name identical to the one she is gaining. The guard used to read
     * {@code vessel.getName()} either way, so the rename caller — which passes exactly that —
     * compared the value against itself and returned every single time. FWN SOLIDE became
     * LADY VIOLETTA with the rename in the change log and no former name anywhere, and the
     * next circular calling her FWN SOLIDE would have found nothing.
     *
     * @param name   the name to file
     * @param keeping the name she will carry afterwards; filing is skipped when they are the
     *                same, because a former name identical to the current one answers nothing
     */
    private void rememberExName(Vessel vessel, String name, String keeping) {
        String trimmed = Extraction.text(name);
        if (trimmed == null) return;
        String stays = Extraction.text(keeping);
        if (stays != null && trimmed.equalsIgnoreCase(stays)) return;
        if (exNames.existsByVesselIdAndNameIgnoreCase(vessel.getId(), trimmed)) return;
        VesselExName ex = new VesselExName();
        ex.setVessel(vessel);
        ex.setName(trimmed);
        ex.setSource(VesselExName.SOURCE_MAIL);
        exNames.save(ex);
    }

    private Cargo createCargo(ResolvedCargo r, MailMessage message) {
        Extraction.ExtractedCargo p = r.parsed();
        Cargo c = new Cargo();
        c.setCommodity(p.commodity().trim());
        c.setStatus(CargoStatus.OPEN);
        c.setStowageFactor(p.stowageFactor());

        c.setQuantity(p.quantity());
        String unit = Extraction.text(p.quantityUnit());
        if (unit != null) c.setQuantityUnit(unit);
        c.setQuantityTolerance(Extraction.text(p.quantityTolerance()));
        applyQuantityRange(c, p);

        c.setLoadPort(r.loadPort());
        c.setLoadPortText(Extraction.text(p.loadPort()));
        c.setLoadArea(r.loadArea());
        c.setDischargePort(r.dischargePort());
        c.setDischargePortText(Extraction.text(p.dischargePort()));
        c.setDischargeArea(r.dischargeArea());

        c.setLaycanFrom(r.laycanFrom());
        c.setLaycanTo(r.laycanTo());
        c.setLaycanText(Extraction.text(p.laycanText()));

        c.setMaxDraft(p.maxDraft());
        c.setMinDwt(p.minDwt());
        c.setMaxDwt(p.maxDwt());
        c.setMaxAgeYears(p.maxAgeYears() == null ? null : p.maxAgeYears().shortValue());
        c.setRequiresGeared(p.requiresGeared());
        c.setRequiresGrainFitted(p.requiresGrainFitted());
        c.setRequiresImoFitted(p.requiresImoFitted());

        c.setFreightIdea(Extraction.text(p.freightIdea()));
        c.setCommission(Extraction.text(p.commission()));
        c.setTerms(Extraction.text(p.terms()));
        c.setLoadRate(Extraction.text(p.loadRate()));
        c.setDischargeRate(Extraction.text(p.dischargeRate()));

        c.setChartererCompany(r.charterer());
        // The sender is the broker this cargo reached us through, taken from the envelope
        // rather than from the signature block the model read: the mail sync resolved it
        // against the contacts table, which is a better answer than a name in prose.
        c.setBrokerCompany(message.getCompany());
        c.setBrokerPerson(message.getPerson());

        c.setSourceMailMessage(message);
        c.setFromMail(true);
        c.setReceivedAt(reportedAt(message));
        c.setNotes(noteFor(p, r));
        return cargoes.save(c);
    }

    /**
     * The charterer's name when it named nobody this application has a row for.
     *
     * <p>Kept as words rather than dropped or turned into a company. The importer's rule
     * applies: an unrecognised name is a lead, and creating a company from one line of a
     * broker's email is how a companies table fills with spelling variants of four firms.
     */
    private static String noteFor(Extraction.ExtractedCargo p, ResolvedCargo r) {
        List<String> lines = new ArrayList<>();
        String note = Extraction.text(p.notes());
        if (note != null) lines.add(note);
        String charterer = Extraction.text(p.charterer());
        if (charterer != null && r.charterer() == null) {
            lines.add("Charterer as written: " + charterer);
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private void applyQuantityRange(Cargo c, Extraction.ExtractedCargo p) {
        if (p.quantityMin() != null || p.quantityMax() != null) {
            c.setQuantityMin(p.quantityMin());
            c.setQuantityMax(p.quantityMax());
            return;
        }
        // Same rule as the form: a percentage is arithmetic, MOLOO is not, and a guessed
        // five per cent would exclude ships that fit with nothing on screen ever saying so.
        Optional<QuantityTolerance.Range> range =
                QuantityTolerance.rangeOf(p.quantity(), Extraction.text(p.quantityTolerance()));
        c.setQuantityMin(range.map(QuantityTolerance.Range::min).orElse(null));
        c.setQuantityMax(range.map(QuantityTolerance.Range::max).orElse(null));
    }

    private void addSource(Cargo cargo, MailMessage message, String note) {
        if (message != null && cargo.getId() != null
                && cargoSources.existsByCargoIdAndMailMessageId(cargo.getId(), message.getId())) {
            return;
        }
        CargoSource source = new CargoSource();
        source.setCargo(cargo);
        source.setMailMessage(message);
        source.setReportedByCompany(message == null ? null : message.getCompany());
        source.setReportedByPerson(message == null ? null : message.getPerson());
        source.setFromAddress(message == null ? null : message.getFromAddress());
        source.setReportedAt(reportedAt(message));
        source.setNotes(note);
        cargoSources.save(source);
    }

    // ------------------------------------------------------------------ internals

    ResolvedCargo resolve(Extraction.ExtractedCargo c) {
        Port loadPort = resolver.resolvePort(c.loadPort());
        Port dischargePort = resolver.resolvePort(c.dischargePort());
        return new ResolvedCargo(c,
                loadPort,
                resolver.resolveArea(c.loadArea(), c.loadPort(), loadPort),
                dischargePort,
                resolver.resolveArea(c.dischargeArea(), c.dischargePort(), dischargePort),
                IntakeResolver.date(c.laycanFrom()),
                IntakeResolver.date(c.laycanTo()),
                resolver.resolveCompany(c.charterer()));
    }

    /**
     * When we were told.
     *
     * <p>The sender's own clock where there is one, for the reason the corpus export prefers
     * it: a message that sat in a queue overnight would otherwise be dated a day after the
     * laycan it announces. Received is the fallback for mail carrying no Date header.
     */
    private static OffsetDateTime reportedAt(MailMessage message) {
        if (message == null) return OffsetDateTime.now();
        var when = message.getSentAt() != null ? message.getSentAt() : message.getReceivedAt();
        return when == null ? OffsetDateTime.now()
                : when.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static boolean sameReporter(VesselPosition p, Company reporter) {
        Long existing = p.getReportedByCompany() == null ? null : p.getReportedByCompany().getId();
        Long incoming = reporter == null ? null : reporter.getId();
        return Objects.equals(existing, incoming);
    }

    /** Identical in every field that says where and when she is free. */
    private static boolean sameReading(VesselPosition p, Port openPort, TradeArea openArea,
                                       LocalDate openFrom, LocalDate openTo,
                                       Extraction.ExtractedVessel v) {
        return Objects.equals(idOf(p.getOpenPort()), idOf(openPort))
                && Objects.equals(idOf(p.getOpenArea()), idOf(openArea))
                && Objects.equals(p.getOpenFrom(), openFrom)
                && Objects.equals(p.getOpenTo(), openTo)
                && looseEquals(p.getOpenPortText(), v.openPort())
                && looseEquals(p.getOpenText(), v.openText());
    }

    private static Long idOf(Port p) {
        return p == null ? null : p.getId();
    }

    private static Long idOf(TradeArea a) {
        return a == null ? null : a.getId();
    }

    private static boolean looseEquals(String a, String b) {
        String x = Extraction.text(a);
        String y = Extraction.text(b);
        if (x == null || y == null) return Objects.equals(x, y);
        return x.replaceAll("\\s+", " ").equalsIgnoreCase(y.replaceAll("\\s+", " "));
    }

    /** The same fields disagreeing about the same values — the question already asked. */

    /**
     * How she was identified, as a code rather than a sentence.
     *
     * <p>The screen ranks these — an IMO match is near-certain, a former-name match is the
     * one worth a second look — and it cannot rank prose. It was prose first, which meant the
     * browser would have had to match on English to colour a tag.
     *
     * <p>Items raised before this change carry the sentence instead. The UI prints an
     * unrecognised value as it stands rather than dropping it, which is the whole reason the
     * payload is read leniently.
     */
    private static String matchNote(IntakeResolver.VesselMatch how) {
        return how == IntakeResolver.VesselMatch.NONE ? null : how.name();
    }

    private static String describe(Cargo c) {
        StringBuilder sb = new StringBuilder(c.getCommodity());
        if (c.getQuantity() != null) {
            sb.append(", ").append(c.getQuantity().stripTrailingZeros().toPlainString())
                    .append(' ').append(c.getQuantityUnit());
        }
        if (c.getLoadPortText() != null) sb.append(" from ").append(c.getLoadPortText());
        else if (c.getLoadPort() != null) sb.append(" from ").append(c.getLoadPort().getName());
        return sb.toString();
    }

    private static String subjectOf(MailMessage m) {
        String subject = m == null ? null : m.getSubject();
        return subject == null || subject.isBlank() ? "(no subject)" : subject.strip();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 255 ? s : s.substring(0, 255);
    }

    private String write(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the review item: " + e.getMessage(), e);
        }
    }

    /**
     * Read a payload back, tolerating one written by an older build.
     *
     * <p>Null rather than an exception, so a shape that has moved costs one unreadable item
     * on a screen rather than a 500 on the list that holds it. {@link #require} is the
     * version used where the payload is about to be acted on and its absence is fatal.
     */
    <T> T read(IntakeItem item, Class<T> type) {
        try {
            return json.readValue(item.getPayload(), type);
        } catch (Exception e) {
            log.warn("Intake item {} has an unreadable payload: {}", item.getId(), e.getMessage());
            return null;
        }
    }

    private <T> T require(IntakeItem item, Class<T> type) {
        T payload = read(item, type);
        if (payload == null) {
            throw new IllegalArgumentException(
                    "This item was raised by an older version and can no longer be applied. "
                            + "Discard it and re-parse the email.");
        }
        return payload;
    }

    /** Numbers the tab's header prints. */
    public record Counts(long pending, long accepted, long rejected) {
    }

    @Transactional(readOnly = true)
    public Counts counts() {
        return new Counts(
                items.countByStatus(IntakeItemStatus.PENDING),
                items.countByStatus(IntakeItemStatus.ACCEPTED),
                items.countByStatus(IntakeItemStatus.REJECTED));
    }
}
