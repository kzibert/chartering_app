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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turning one read email into rows — and into the questions it could not answer.
 *
 * <p><b>Mail is no longer the only door.</b> A board post — ship.gr's Open Cargoes and Open
 * Ships pages are circulars the same firms paste by hand — arrives as the same text and is read
 * by the same model, so everything below works on an {@link Arrival} rather than on a
 * {@code MailMessage}. What differs is where "who told us" comes from: an envelope the mail sync
 * has already matched against the contacts table, or the signature block at the foot of the
 * post. Nothing downstream of that distinction has to know which.
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
    private final IntakeFieldDecisionRepository decisions;
    private final IntakeVesselAliasRepository aliases;
    private final VesselPositionRepository positions;
    private final IntakeResolver resolver;
    private final CompanyStyleIntake styles;
    private final com.chartering.config.ParserProperties props;
    private final com.chartering.repository.CompanyRepository companies;
    private final IntakePasteService paste;
    private final com.chartering.repository.PersonRepository people;
    private final com.chartering.service.SettingsService settingsService;
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
        // The signature block, read before anything else is filed, because a post off a board
        // has no other way of saying who is talking - and on a mailed circular it is the
        // question the fourth kind of review item asks. Costs no model call: CompanyStyleReader
        // reads shapes, not prose.
        CompanyStyleIntake.Reading signature = styles.read(textOf(parsed), extraction);
        Arrival arrival = arrivalOf(parsed, signature);
        // Names the transaction's whole change set, so the gap fills a circular writes read
        // in the History tab as one event with a cause rather than as scattered edits. The
        // importer uses it the same way for the same reason.
        ChangeContext.describe((arrival.isMail() ? "Read from mail: " : "Read from the web: ")
                + arrival.label());

        int positionsApplied = 0;
        int cargoesApplied = 0;
        int itemsRaised = 0;

        for (Extraction.ExtractedVessel v : extraction.vesselsOrEmpty()) {
            if (!v.isUsable()) continue;
            VesselOutcome outcome = applyVessel(parsed, arrival, v);
            positionsApplied += outcome.positionApplied() ? 1 : 0;
            itemsRaised += outcome.itemsRaised();
        }

        for (Extraction.ExtractedCargo c : extraction.cargoesOrEmpty()) {
            if (!c.isUsable()) continue;
            CargoOutcome outcome = applyCargo(parsed, arrival, c);
            cargoesApplied += outcome.cargoApplied() ? 1 : 0;
            itemsRaised += outcome.itemsRaised();
        }

        if (raiseCompanyDetails(parsed, arrival, signature)) itemsRaised++;

        return new ApplyOutcome(positionsApplied, cargoesApplied, itemsRaised);
    }

    /**
     * What this parse was read out of, with the sender placed.
     *
     * <p>For mail the sync has already done the work: the envelope was matched against the
     * contacts table when the message landed, which beats any reading of the prose below it.
     * For a post there is no envelope, so the only firm named anywhere is the one that signed
     * it — matched the way the paste screen matches a signature, and left null when nothing on
     * file carries identity evidence for it. A null reporter is honest rather than broken: the
     * position is still worth filing, and it is precisely the case the {@code COMPANY_DETAILS}
     * question raised beside it exists to close.
     */
    private Arrival arrivalOf(ParsedEmail parsed, CompanyStyleIntake.Reading signature) {
        if (parsed.getMailMessage() != null) return Arrival.of(parsed.getMailMessage());
        Company signer = signature.companyId() == null ? null
                : companies.findById(signature.companyId()).orElse(null);
        return Arrival.of(parsed.getFeedItem(), signature, signer, signerPerson(signer, signature));
    }

    /**
     * Which person at the firm signed it, where the block names one this desk already holds.
     *
     * <p>Only an exact full name, and only within the matched firm. A looser rule belongs on the
     * review screen, where a person can see both names — {@code IntakePasteService.compare}
     * offers a surname-and-initial suggestion and flags it as one. Guessing here would file a
     * cargo against the wrong colleague with nothing on any screen ever saying so.
     */
    private Person signerPerson(Company company, CompanyStyleIntake.Reading signature) {
        if (company == null || signature.style() == null || signature.style().people().isEmpty()) {
            return null;
        }
        List<Person> onFile = people.findByCompanyIds(List.of(company.getId()));
        for (CompanyStyleReader.Person read : signature.style().people()) {
            if (read.fullName() == null) continue;
            for (Person p : onFile) {
                if (p.getFullName() != null
                        && p.getFullName().strip().equalsIgnoreCase(read.fullName().strip())) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * The same, for the paths that start from a stored item rather than from a fresh parse.
     *
     * <p>A post's signature is read again here rather than stored on the row, and the reason is
     * the one this whole table follows: what is stored is what a reading <em>produced</em> — the
     * reporter on the position, the broker on the cargo — not the intermediate step. Reading a
     * signature costs no model call and no network, so keeping a second copy of it in step with
     * the first would be work with a bug in it and nothing bought.
     */
    private Arrival arrivalOf(ParsedEmail parsed) {
        if (parsed.getMailMessage() != null) return Arrival.of(parsed.getMailMessage());
        return arrivalOf(parsed, styles.read(textOf(parsed), null));
    }

    /**
     * The text a parse was read out of, whichever door it came in through.
     *
     * <p><b>Cut to the same length the model was given</b>, which is not tidiness. What runs
     * past {@code maxBodyChars} on a mailed circular is a quoted chain, and a quoted chain
     * ends in somebody else's signature — often several, oldest last. Reading the whole body
     * would let a firm three replies down be proposed as the sender of this one, and the
     * queue would fill with questions about correspondents nobody wrote to. Cutting here also
     * keeps one promise worth keeping: the signature that is compared is inside the text the
     * model actually read.
     */
    private String textOf(ParsedEmail parsed) {
        String text = parsed.getMailMessage() != null
                ? parsed.getMailMessage().getBodyText()
                : (parsed.getFeedItem() == null ? null : parsed.getFeedItem().getText());
        int max = props.getMaxBodyChars();
        return text == null || text.length() <= max ? text : text.substring(0, max);
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
    private VesselOutcome applyVessel(ParsedEmail parsed, Arrival arrival,
                                      Extraction.ExtractedVessel v) {
        Long reporter = companyIdOf(arrival);
        IntakeResolver.ResolvedVessel match = resolver.resolveVessel(v, reporter);
        if (!match.found()) {
            return new VesselOutcome(false, raiseNewVessel(parsed, arrival, v) ? 1 : 0);
        }

        Vessel vessel = match.vessel();
        VesselFieldDiff.Result diff = VesselFieldDiff.compare(vessel, v);
        // What this firm has already been told about is not a question any more. Asked before
        // the item is raised rather than when it is opened, so the queue's count is the number
        // of questions actually waiting.
        VesselFieldDiff.Result asking = withoutSettled(vessel, v, diff, Collections.singletonList(reporter));
        int raised = 0;
        if (asking.hasConflicts()
                && raiseVesselFields(parsed, arrival, vessel, match.how(), v, asking)) {
            raised = 1;
        }
        boolean applied = recordPosition(vessel, v, arrival);
        return new VesselOutcome(applied, raised);
    }

    /**
     * The same disagreements, less the ones this firm has already been told about.
     *
     * <p><b>The repeat this exists to stop.</b> A broker re-sends his position list every
     * morning. Where his reading of a hull differs from the record the reviewer answers it —
     * and the next morning the identical email raises the identical question, because the
     * answer was written on the item and the item was closed. ANGORA asked about JELENA's bale
     * four days running; nothing recorded that this value, from this firm, about this field,
     * had been looked at and turned down.
     *
     * <p>Scoped to the correspondent, which is the judgement in it: that one broker is wrong
     * about her bale says nothing about the next one, and a second firm reporting the same
     * figure is a second opinion nobody here has weighed. Compared through
     * {@link VesselFieldDiff#reportsValue} rather than as text, so a figure the same broker
     * rounds differently on Wednesday is still the one settled on Tuesday.
     *
     * @param askedBy the firms the rows would be attributed to; a null among them is the
     *                sender the sync could not place, which is a value here and not a wildcard
     */
    VesselFieldDiff.Result withoutSettled(Vessel vessel, Extraction.ExtractedVessel reading,
                                          VesselFieldDiff.Result diff, Collection<Long> askedBy) {
        if (!diff.hasConflicts() || vessel.getId() == null) return diff;
        List<IntakeFieldDecision> settled = decisions.forVessel(vessel.getId());
        if (settled.isEmpty()) return diff;

        List<FieldDiff> asking = new ArrayList<>();
        for (FieldDiff row : diff.conflicts()) {
            if (!isSettled(settled, vessel, reading, row.field(), askedBy)) asking.add(row);
        }
        return new VesselFieldDiff.Result(List.copyOf(asking), diff.filled());
    }

    private static boolean isSettled(List<IntakeFieldDecision> settled, Vessel vessel,
                                     Extraction.ExtractedVessel reading, String field,
                                     Collection<Long> askedBy) {
        for (IntakeFieldDecision d : settled) {
            if (!d.getField().equals(field)) continue;
            Long who = d.getReportedByCompany() == null ? null : d.getReportedByCompany().getId();
            if (!askedBy.contains(who)) continue;
            if (VesselFieldDiff.reportsValue(vessel, reading, field, d.getValueText())) return true;
        }
        return false;
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
    private boolean recordPosition(Vessel vessel, Extraction.ExtractedVessel v, Arrival arrival) {
        Port openPort = resolver.resolvePort(v.openPort());
        TradeArea openArea = resolver.resolveArea(v.openArea(), v.openPort(), openPort);
        LocalDate openFrom = IntakeResolver.date(v.openFrom());
        LocalDate openTo = IntakeResolver.date(v.openTo());
        Company reporter = arrival.company();
        OffsetDateTime reportedAt = arrival.reportedAt();

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
                p.setSourceMailMessage(arrival.message());
                p.setSourceFeedItem(arrival.post());
                p.setFromMail(arrival.isMail());
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
        p.setReportedByPerson(arrival.person());
        p.setSourceMailMessage(arrival.message());
        p.setSourceFeedItem(arrival.post());
        p.setFromMail(arrival.isMail());
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
    private CargoOutcome applyCargo(ParsedEmail parsed, Arrival arrival,
                                    Extraction.ExtractedCargo c) {
        ResolvedCargo resolved = resolve(c);

        List<Cargo> candidates = cargoes.findDuplicateCandidates(CargoService.RECOGNISED_ON_ARRIVAL_STATUSES);
        Optional<CargoMatcher.Candidate> duplicate =
                CargoMatcher.findDuplicate(c, candidates, resolved.loadPort(), resolved.loadArea());

        if (duplicate.isPresent()) {
            return new CargoOutcome(false,
                    raiseCargoMerge(parsed, arrival, resolved, duplicate.get()) ? 1 : 0);
        }

        Cargo cargo = createCargo(resolved, arrival);
        addSource(cargo, arrival, null);
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
    private boolean raiseNewVessel(ParsedEmail parsed, Arrival arrival,
                                   Extraction.ExtractedVessel v) {
        String name = Extraction.text(v.name());
        if (!items.pendingNewVessel(name).isEmpty()) return false;

        String searchedBy = IntakeResolver.normaliseImo(v.imo()) != null
                ? "IMO " + IntakeResolver.normaliseImo(v.imo()) + " and the name \"" + name + "\""
                : "the name \"" + name + "\"";
        IntakePayloads.NewVessel payload =
                new IntakePayloads.NewVessel(v, searchedBy, resolver.suggest(v));
        addSource(save(parsed, IntakeItemKind.NEW_VESSEL, null, null, name, payload),
                parsed, arrival);
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
    private boolean raiseVesselFields(ParsedEmail parsed, Arrival arrival, Vessel vessel,
                                      IntakeResolver.VesselMatch how,
                                      Extraction.ExtractedVessel v,
                                      VesselFieldDiff.Result diff) {
        for (IntakeItem pending : items.pendingForVessel(IntakeItemKind.VESSEL_FIELDS, vessel.getId())) {
            IntakePayloads.VesselFields existing =
                    read(pending, IntakePayloads.VesselFields.class);
            if (existing == null) continue;
            mergeInto(pending, existing, how, v, diff);
            addSource(pending, parsed, arrival);
            // Not a new question, so the sweep's "raised" count does not grow. The email is on
            // the item and the reviewer will see it; the queue is no longer than it was.
            return false;
        }
        IntakePayloads.VesselFields payload = new IntakePayloads.VesselFields(
                vessel.getId(), vessel.getName(), matchNote(how), v,
                diff.conflicts(), diff.filled());
        IntakeItem item = save(parsed, IntakeItemKind.VESSEL_FIELDS, vessel.getId(), null,
                vessel.getName(), payload);
        addSource(item, parsed, arrival);
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
        recordPosition(vessel, payload.vessel(), arrivalOf(item.getParsedEmail()));
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
        // A stored row for a field no longer compared (the vessel type was, until the column
        // became a list of categories) is dropped rather than carried into the merged question.
        Map<String, String> compared = VesselFieldDiff.fields();
        for (FieldDiff d : existing.diffs()) {
            if (compared.containsKey(d.field())) byField.put(d.field(), d);
        }
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
    private void addSource(IntakeItem item, ParsedEmail parsed, Arrival arrival) {
        if (itemSources.existsByIntakeItemIdAndParsedEmailId(item.getId(), parsed.getId())) return;
        IntakeItemSource source = new IntakeItemSource();
        source.setIntakeItem(item);
        source.setParsedEmail(parsed);
        source.setMailMessage(arrival.message());
        source.setFeedItem(arrival.post());
        source.setReportedByCompany(arrival.company());
        source.setReportedAt(arrival.reportedAt());
        itemSources.save(source);
    }

    private boolean raiseCargoMerge(ParsedEmail parsed, Arrival arrival, ResolvedCargo resolved,
                                    CargoMatcher.Candidate candidate) {
        Cargo existing = candidate.cargo();
        CargoFieldDiff.Result preview = CargoFieldDiff.merge(existing, resolved, false);
        IntakePayloads.CargoMerge payload = new IntakePayloads.CargoMerge(
                resolved.parsed(), existing.getId(), describe(existing),
                candidate.reasons(), preview.filled(), preview.differing());
        addSource(save(parsed, IntakeItemKind.CARGO_MERGE, null, existing.getId(),
                Extraction.text(resolved.parsed().commodity()), payload), parsed, arrival);
        return true;
    }

    /**
     * The firm that signed it, where the record and the signature do not agree.
     *
     * <p><b>Raised from mail as well as from a board, deliberately.</b> The signature at the
     * foot of a mailed circular is the same document as the one on a board post and goes stale
     * the same way; reading one and not the other would mean a firm's details being kept current
     * only while they happened to post publicly. What makes that affordable is that silence is
     * the normal answer — see {@link CompanyStyleIntake}: a firm whose record already holds
     * everything the block says raises nothing, which after the first answer is every regular
     * correspondent for ever.
     *
     * <p><b>Our own mail signs with our own block, and is skipped.</b> The sweep reads the Sent
     * folder as well as the inbox — the same reason {@code cargo_sources} filters the desk's own
     * addresses out of "who told us about this cargo" — and a queue asking whether to create the
     * firm you work for is a queue with an obvious bug in it.
     *
     * <p><b>A rejected reading is not asked again.</b> The other kinds can re-raise on new
     * figures because figures are what they are about; a signature is about a firm, and "do not
     * file these details" would be worthless if the same broker's next list undid it. The
     * fingerprint is what a discard suppresses, so only a signature that has genuinely moved
     * comes back.
     */
    private boolean raiseCompanyDetails(ParsedEmail parsed, Arrival arrival,
                                        CompanyStyleIntake.Reading signature) {
        if (isOurs(arrival, signature)) return false;
        IntakePayloads.CompanyDetails payload = styles.question(signature).orElse(null);
        if (payload == null) return false;
        String name = payload.draft().company().getName();

        // One pending question per firm. A broker signs every list he sends, so without this the
        // queue would carry a row per circular per firm - the failure the vessel rule prevents,
        // at ten times the rate. The newest reading wins, as it does there: the later block is
        // the later statement, and every arrival stays readable from the item.
        List<IntakeItem> pending = payload.companyId() != null
                ? items.pendingForCompany(payload.companyId())
                : items.pendingNewCompany(name);
        if (!pending.isEmpty()) {
            IntakeItem open = pending.get(0);
            open.setPayload(write(payload));
            items.save(open);
            addSource(open, parsed, arrival);
            return false;
        }

        if (items.countRejectedWithStyle(payload.styleHash()) > 0) return false;

        IntakeItem item = save(parsed, IntakeItemKind.COMPANY_DETAILS, null, null, name, payload);
        item.setCompanyId(payload.companyId());
        addSource(item, parsed, arrival);
        return true;
    }

    /**
     * Whether this circular is one of ours.
     *
     * <p>Every address in the block is tested, not only the envelope's: a reply of ours carries
     * the desk's whole signature, and it is the block rather than the From line that would
     * otherwise be proposed as a new company. The list is {@code app_settings}' "My email
     * addresses", the same one the cargo sources screen filters on.
     */
    private boolean isOurs(Arrival arrival, CompanyStyleIntake.Reading signature) {
        Set<String> own = settingsService.ownAddresses();
        if (own.isEmpty()) return false;
        if (com.chartering.service.SettingsService.isOwn(arrival.fromAddress(), own)) return true;
        if (signature == null || signature.style() == null) return false;
        return signature.style().contacts().stream()
                .filter(c -> "email".equals(c.kind()))
                .anyMatch(c -> com.chartering.service.SettingsService.isOwn(c.value(), own));
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
                              Map<String, String> corrections,
                              Long vesselId, String note, String user) {
        IntakeItem item = items.findWithEmailById(id)
                .orElseThrow(() -> new com.chartering.exception.ResourceNotFoundException(
                        "Intake item", id));
        if (item.getStatus() != IntakeItemStatus.PENDING) {
            throw new IllegalArgumentException("This item has already been answered.");
        }
        ChangeContext.describe("Intake: " + item.getKind() + " " + action);

        String summary = switch (item.getKind()) {
            case NEW_VESSEL -> resolveNewVessel(item, action, vesselId, user);
            case VESSEL_FIELDS -> resolveVesselFields(item, action, fields,
                    corrections == null ? Map.of() : corrections, user);
            case CARGO_MERGE -> resolveCargoMerge(item, action);
            case COMPANY_DETAILS -> resolveCompanyDetails(item, action);
        };

        item.setStatus(action == Action.DISCARD ? IntakeItemStatus.REJECTED : IntakeItemStatus.ACCEPTED);
        item.setResolvedAt(OffsetDateTime.now());
        item.setResolvedBy(user);
        item.setResolutionNote(note == null || note.isBlank() ? summary : note.strip());
        return new Resolution(item, summary);
    }

    private String resolveNewVessel(IntakeItem item, Action action, Long vesselId, String user) {
        IntakePayloads.NewVessel payload = require(item, IntakePayloads.NewVessel.class);
        if (action == Action.DISCARD) return "Discarded; no vessel created.";

        Arrival arrival = arrivalOf(item.getParsedEmail());
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

        // Whichever answer it was, this firm's name for her is now settled. The ex-name above
        // cannot carry it where the name the email used is the one she already has - which is
        // every PHANTOM, the case that made this necessary.
        summary += rememberAlias(item, vessel, payload.vessel().name(),
                action == Action.ALTERNATIVE ? IntakeVesselAlias.LINKED : IntakeVesselAlias.CREATED,
                user);

        recordPosition(vessel, payload.vessel(), arrival);
        item.setVesselId(vessel.getId());
        return summary;
    }

    private String resolveVesselFields(IntakeItem item, Action action, List<String> fields,
                                       Map<String, String> corrections, String user) {
        IntakePayloads.VesselFields payload = require(item, IntakePayloads.VesselFields.class);
        Vessel vessel = payload.vesselId() == null ? null
                : vessels.findById(payload.vesselId()).orElse(null);

        if (action == Action.DISCARD) {
            // Every row that was on screen has now been answered "the record is right", and
            // that answer is what stops tomorrow's copy of the same list asking it again.
            int settled = 0;
            if (vessel != null) {
                List<FieldDiff> rows = shown(item, vessel, payload);
                settled = settle(item, vessel, fieldsOf(rows), reported(vessel, payload, rows),
                        IntakeFieldDecision.KEPT, Map.of(), user);
            }
            return "Kept what was on file; nothing changed."
                    + (settled > 0 ? " The same reading will not be raised again." : "");
        }
        if (vessel == null) {
            throw new com.chartering.exception.ResourceNotFoundException("Vessel", payload.vesselId());
        }

        if (action == Action.ALTERNATIVE) return separateVessel(item, payload, vessel, user);

        // What the screen showed: the comparison made now rather than the one stored when the
        // email arrived (see VesselFieldDiff.preview), less anything these senders have already
        // settled — so "accept all" cannot write a figure the screen was not offering.
        List<FieldDiff> onScreen = shown(item, vessel, payload);
        // Read off before anything is written. Correcting her deadweight moves the size a
        // capacity's unit is judged against, so the same reading asked about afterwards could
        // canonicalise differently - and a decision stored under a value the email never
        // reported would never match it again.
        Map<String, String> reported = reported(vessel, payload, onScreen);

        // An empty list means all of them, which is what the "Accept all" button sends. A
        // list that names nothing and meant nothing would be an accept that quietly did
        // nothing, so the UI never sends one.
        List<String> chosen = fields == null || fields.isEmpty()
                ? fieldsOf(onScreen)
                : fields;

        // Read into each field's own type before anything is written, so a value that cannot be
        // one is a sentence on the screen rather than half an accept. parseCorrection throws
        // with the field's own name in it.
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : corrections.entrySet()) {
            if (!chosen.contains(e.getKey())) continue;
            typed.put(e.getKey(), VesselFieldDiff.parseCorrection(e.getKey(), e.getValue()));
        }

        // A rename is the one accepted field that has a second effect: the name she is
        // losing is the name this database has been finding her under, and dropping it would
        // make every older position list unsearchable for her. She gains the corrected name
        // where there is one, and the email's otherwise.
        if (chosen.contains("name")) {
            Object corrected = typed.get("name");
            rememberExName(vessel, vessel.getName(), corrected != null ? (String) corrected
                    : payload.vessel() == null ? null : payload.vessel().name());
        }

        List<String> written =
                VesselFieldDiff.applySelected(vessel, payload.vessel(), chosen, typed);

        // Two answers leave the email as wrong tomorrow as it is today, and both are recorded:
        // a row left unticked, and a row the reviewer overrode. An accepted value needs no row
        // — the record now holds it, so tomorrow's list agrees with it.
        settle(item, vessel,
                fieldsOf(onScreen).stream().filter(f -> !chosen.contains(f)).toList(),
                reported, IntakeFieldDecision.KEPT, Map.of(), user);
        settle(item, vessel, typed.keySet(), reported, IntakeFieldDecision.CORRECTED, typed, user);

        if (written.isEmpty()) return "Nothing changed — the record already reads that way.";
        String summary = "Updated "
                + String.join(", ", written.stream().map(VesselFieldDiff::labelOf).toList())
                + " on " + vessel.getName() + ".";
        if (!typed.isEmpty()) {
            summary += " " + String.join(", ",
                    typed.keySet().stream().map(VesselFieldDiff::labelOf).toList())
                    + (typed.size() == 1 ? " was corrected by hand." : " were corrected by hand.");
        }
        return summary;
    }

    /**
     * The rows the reviewer was actually looking at.
     *
     * <p>The stored rows are the comparison made the day the email arrived, under that day's
     * rules; the screen re-asks against the record as it stands and drops what these senders
     * have already settled. An accept has to be answering that same list, or "accept all" would
     * write a figure the screen never offered.
     */
    List<FieldDiff> shown(IntakeItem item, Vessel vessel, IntakePayloads.VesselFields payload) {
        if (payload.vessel() == null) return payload.diffs() == null ? List.of() : payload.diffs();
        return withoutSettled(vessel, payload.vessel(),
                VesselFieldDiff.preview(vessel, payload.vessel()),
                senderIdsOf(item)).conflicts();
    }

    private static List<String> fieldsOf(List<FieldDiff> rows) {
        return rows.stream().map(FieldDiff::field).toList();
    }

    /**
     * What the email reported for each row, canonically: the value as it was compared, with no
     * unit on it. This is what a decision is stored under and what a later reading is tested
     * against, and it is never the printed string - two renderings of one figure would be two
     * declined values, neither recognising the other.
     */
    private static Map<String, String> reported(Vessel vessel, IntakePayloads.VesselFields payload,
                                                List<FieldDiff> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        if (payload.vessel() == null) return out;
        for (FieldDiff row : rows) {
            String value = VesselFieldDiff.incomingValue(vessel, payload.vessel(), row.field());
            if (value != null) out.put(row.field(), value);
        }
        return out;
    }

    /**
     * Record that these values, from the firms that sent them, have been answered.
     *
     * <p>One row per field per sender. An item several brokers raised is one question and gets
     * one answer, so the answer covers all of them - but as separate rows, because each is a
     * statement about what that firm reported and any of them may later be the only one still
     * saying it.
     *
     * <p>Idempotent against what is already stored: re-answering the same value updates the
     * existing row rather than failing on the unique index, which is what the second copy of a
     * re-parsed email would otherwise do.
     *
     * @param typed the corrected values, for the CORRECTED rows; empty for KEPT
     * @return how many decisions were written or refreshed
     */
    private int settle(IntakeItem item, Vessel vessel, Collection<String> fields,
                       Map<String, String> reported, String decision,
                       Map<String, Object> typed, String user) {
        if (fields.isEmpty()) return 0;
        List<Company> senders = sendersOf(item);
        List<IntakeFieldDecision> existing = decisions.forVessel(vessel.getId());

        int written = 0;
        for (String field : fields) {
            String value = reported.get(field);
            // Nothing to recognise it by next time. A field the email did not actually report
            // cannot have been declined, so there is no row to write.
            if (value == null) continue;
            for (Company sender : senders) {
                Long who = sender == null ? null : sender.getId();
                IntakeFieldDecision row = existing.stream()
                        .filter(d -> d.getField().equals(field) && value.equals(d.getValueText()))
                        .filter(d -> Objects.equals(who,
                                d.getReportedByCompany() == null ? null : d.getReportedByCompany().getId()))
                        .findFirst()
                        .orElseGet(IntakeFieldDecision::new);
                row.setVesselId(vessel.getId());
                row.setField(field);
                row.setReportedByCompany(sender);
                row.setValueText(value);
                row.setDecision(decision);
                row.setCorrectedTo(VesselFieldDiff.canonical(typed.get(field)));
                row.setIntakeItemId(item.getId());
                row.setDecidedAt(OffsetDateTime.now());
                row.setDecidedBy(user);
                decisions.save(row);
                written++;
            }
        }
        return written;
    }

    /**
     * Record which hull this item's senders mean by the name their email used.
     *
     * <p><b>Why {@code vessel_ex_names} cannot carry it.</b> A former name is a fact about the
     * ship - she used to be called that, it is true for everyone, and it is what lets any
     * broker's list find her. Two hulls here are called PHANTOM and neither carries an IMO, so
     * the name tier finds two rows and refuses to choose; the name the email used is the one
     * she already has, so linking had nothing to file, and filing it anyway would have said
     * something false about the other PHANTOM as well. What settles it is whose list it is.
     *
     * <p>Nothing is recorded where the sync could not put the address to a firm: an alias with
     * nobody behind it is a claim about the name itself, and that is the arbitrary pick the
     * resolver exists to refuse.
     *
     * @return a sentence for the resolution note, or "" when there was nothing to record
     */
    private String rememberAlias(IntakeItem item, Vessel vessel, String nameAsWritten,
                                 String source, String user) {
        String name = Extraction.text(nameAsWritten);
        if (name == null || vessel.getId() == null) return "";
        String key = IntakeVesselAlias.key(name);

        List<String> firms = new ArrayList<>();
        for (Company sender : sendersOf(item)) {
            if (sender == null) continue;
            IntakeVesselAlias alias = aliases.find(sender.getId(), key)
                    .orElseGet(IntakeVesselAlias::new);
            // Replaced rather than added to: an owner sells a ship and takes the name to the
            // next one, and this firm's later statement is the one to act on.
            alias.setVesselId(vessel.getId());
            alias.setReportedByCompany(sender);
            alias.setName(name);
            alias.setNameKey(key);
            alias.setSource(source);
            alias.setCreatedAt(OffsetDateTime.now());
            alias.setCreatedBy(user);
            aliases.save(alias);
            if (!firms.contains(sender.getName())) firms.add(sender.getName());
        }
        if (firms.isEmpty()) return "";
        return " \"" + name + "\" from " + String.join(", ", firms)
                + " will be read as this ship from now on.";
    }

    /** Every firm behind an item, one entry each, with null for a sender the sync could not place. */
    private List<Company> sendersOf(IntakeItem item) {
        List<Company> out = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (IntakeItemSource source : itemSources.forItem(item.getId())) {
            Company company = source.getReportedByCompany();
            Long id = company == null ? null : company.getId();
            if (seen.contains(id)) continue;
            seen.add(id);
            out.add(company);
        }
        // An item raised before sources were kept, or one whose only arrival could not be
        // placed: the question was still asked by somebody, and "nobody" is a value here.
        if (out.isEmpty()) out.add(companyOf(item.getParsedEmail().getMailMessage()));
        return out;
    }

    private List<Long> senderIdsOf(IntakeItem item) {
        return sendersOf(item).stream().map(c -> c == null ? null : c.getId()).toList();
    }

    private static Company companyOf(MailMessage message) {
        return message == null ? null : message.getCompany();
    }

    /**
     * The firm an arrival is from, as an id.
     *
     * <p>Out of the mail that is the envelope the sync resolved; off a board it is the
     * signature block. Both answer "who is telling us this", which is what a settled decision
     * is scoped to — that one broker is wrong about a bale says nothing about what the next
     * one reports, and a board post is a correspondent like any other.
     */
    private static Long companyIdOf(Arrival arrival) {
        Company company = arrival == null ? null : arrival.company();
        return company == null ? null : company.getId();
    }

    private static Long companyIdOf(MailMessage message) {
        Company company = companyOf(message);
        return company == null ? null : company.getId();
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
                                  Vessel matched, String user) {
        Arrival arrival = arrivalOf(item.getParsedEmail());

        int withdrawn = 0;
        for (VesselPosition p : positions.findByVesselIdOrderByReportedAtDesc(matched.getId())) {
            // Only what this arrival put there — a row an earlier circular created and this one
            // merely re-confirmed belongs to that earlier reading. Asked of both doors, because
            // by now a reading may have come off a board rather than out of the mail.
            if (p.getStatus() == PositionStatus.LIVE
                    && (arrival.is(p.getSourceMailMessage()) || arrival.is(p.getSourceFeedItem()))) {
                p.setStatus(PositionStatus.WITHDRAWN);
                withdrawn++;
            }
        }

        Vessel created = createVessel(payload.vessel());
        recordPosition(created, payload.vessel(), arrival);
        item.setVesselId(created.getId());
        // These senders were describing this hull all along, whatever the name matched. Without
        // it the next copy of the same list matches the wrong ship again and asks again.
        rememberAlias(item, created, payload.vessel().name(), IntakeVesselAlias.CREATED, user);

        return "Created " + created.getName() + " as a separate vessel and filed the position "
                + "on her" + (withdrawn > 0
                ? "; the reading this email put on " + matched.getName() + " was withdrawn."
                : ". Nothing was taken off " + matched.getName() + " - this email had added "
                  + "no live position there.");
    }

    private String resolveCargoMerge(IntakeItem item, Action action) {
        IntakePayloads.CargoMerge payload = require(item, IntakePayloads.CargoMerge.class);
        Arrival arrival = arrivalOf(item.getParsedEmail());
        ResolvedCargo resolved = resolve(payload.cargo());

        if (action == Action.DISCARD) return "Discarded; no cargo written.";

        if (action == Action.ALTERNATIVE) {
            Cargo cargo = createCargo(resolved, arrival);
            addSource(cargo, arrival, "Kept separate from cargo #" + payload.candidateId());
            item.setCargoId(cargo.getId());
            return "Kept as a separate cargo.";
        }

        Cargo existing = cargoes.findById(payload.candidateId()).orElseThrow(() ->
                new com.chartering.exception.ResourceNotFoundException("Cargo", payload.candidateId()));
        CargoFieldDiff.Result merged = CargoFieldDiff.merge(existing, resolved, true);
        addSource(existing, arrival, null);
        return merged.filled().isEmpty()
                ? "Merged; the cargo already held everything this email said."
                : "Merged, filling " + merged.filled().size() + " empty field(s).";
    }

    /**
     * The only answer to a company question that goes through {@link #resolve}.
     *
     * <p>Accepting one writes a firm, its people and its addresses from a form the reviewer has
     * edited, which is a request body rather than a list of field names — so it has its own
     * endpoint and its own method, {@link #acceptCompanyDetails}. What is left here is the
     * refusal, and the refusal is the half that needed saying: the fingerprint in the payload is
     * what stops the same signature asking again next Monday.
     */
    private String resolveCompanyDetails(IntakeItem item, Action action) {
        if (action != Action.DISCARD) {
            throw new IllegalArgumentException(
                    "Answer a company question by reviewing its details, not by accepting it "
                            + "whole — the screen sends what you ticked.");
        }
        return "Discarded; the firm was left as it is. This signature will not be raised again "
                + "unless it changes.";
    }

    /**
     * Accept a company question as the reviewer left it.
     *
     * <p><b>Delegates to {@link IntakePasteService#acceptCompany}</b>, which is the same call the
     * paste modal makes, because the decision is the same decision: a signature is a lead sheet,
     * a firm on file changes only where it was ticked, everything else only adds, and nothing
     * arrives flagged main or for circulation. Two implementations of that would be two ideas of
     * what a signature is allowed to overwrite, and the one nobody was looking at would be the
     * one that overwrote a city somebody had typed.
     *
     * <p>Its own transaction and its own change-set name, the split {@link #applyLookup} makes:
     * "who this firm is" and "what the parser read out of a circular" are two origins, and the
     * History tab is worth being able to tell them apart.
     */
    @Transactional
    public com.chartering.dto.IntakePasteCompanyResponse acceptCompanyDetails(
            Long id, com.chartering.dto.IntakePasteCompanyRequest req, String user) {
        IntakeItem item = items.findWithEmailById(id).orElseThrow(() ->
                new com.chartering.exception.ResourceNotFoundException("Intake item", id));
        if (item.getStatus() != IntakeItemStatus.PENDING) {
            throw new IllegalArgumentException("This item has already been answered.");
        }
        if (item.getKind() != IntakeItemKind.COMPANY_DETAILS) {
            throw new IllegalArgumentException("That item is not a question about a company.");
        }

        var applied = paste.acceptCompany(req);
        ChangeContext.describe("Intake: company details from " + arrivalOf(item.getParsedEmail()).label());

        item.setCompanyId(applied.companyId());
        item.setStatus(IntakeItemStatus.ACCEPTED);
        item.setResolvedAt(OffsetDateTime.now());
        item.setResolvedBy(user);
        String summary = applied.created()
                ? "Created " + applied.companyName() + " with " + applied.peopleAdded()
                        + " person(s) and " + applied.contactsAdded() + " address(es)."
                : "Updated " + applied.companyName() + ": " + applied.companyFieldsUpdated()
                        + " field(s), " + applied.peopleAdded() + " person(s) added, "
                        + applied.contactsAdded() + " address(es) added.";
        item.setResolutionNote(summary);
        // The paste screen's own answer, passed straight back: the card that sent this renders
        // "created X, 2 people added, 1 address already on file" out of it, and a second shape
        // would be a second wording of the same event on two screens.
        return applied;
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
        if (companyId == null) return arrivalOf(item.getParsedEmail()).company();
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
        // A category, never the circular's own description: see VesselTypes. Null when the
        // wording names none, which the vessel form shows as a type still to be chosen.
        vessel.setVesselType(com.chartering.service.VesselTypes.canonical(v.vesselType()));
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

    private Cargo createCargo(ResolvedCargo r, Arrival arrival) {
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
        // The sender is the broker this cargo reached us through. Out of the mail that is the
        // envelope, which the sync already matched against the contacts table and which beats
        // any reading of the prose below it; off a board there is no envelope, so it is the
        // signature block, matched the same way the paste screen matches one. Null where
        // nothing on file carries identity evidence for the firm — which is the question the
        // COMPANY_DETAILS item raised beside this cargo exists to close.
        c.setBrokerCompany(arrival.company());
        c.setBrokerPerson(arrival.person());

        c.setSourceKind(arrival.kind());
        c.setSourceMailMessage(arrival.message());
        c.setSourceFeedItem(arrival.post());
        c.setReceivedAt(arrival.reportedAt());
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

    /**
     * Record that this arrival told us about this cargo.
     *
     * <p>Guarded against a second row for the same arrival, whichever door it came in through:
     * re-parsing a message, or a board re-listing a post under the same entry, must read as
     * "already counted" rather than as a duplicate broker on the cargo's own drawer.
     */
    private void addSource(Cargo cargo, Arrival arrival, String note) {
        if (cargo.getId() != null && alreadySourced(cargo, arrival)) return;
        CargoSource source = new CargoSource();
        source.setCargo(cargo);
        source.setMailMessage(arrival.message());
        source.setFeedItem(arrival.post());
        source.setReportedByCompany(arrival.company());
        source.setReportedByPerson(arrival.person());
        source.setFromAddress(arrival.fromAddress());
        source.setReportedAt(arrival.reportedAt());
        source.setNotes(note);
        cargoSources.save(source);
    }

    private boolean alreadySourced(Cargo cargo, Arrival arrival) {
        if (arrival.message() != null) {
            return cargoSources.existsByCargoIdAndMailMessageId(
                    cargo.getId(), arrival.message().getId());
        }
        return arrival.post() != null && cargoSources.existsByCargoIdAndFeedItemId(
                cargo.getId(), arrival.post().getId());
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
        // The question reads differently about a cargo already turned down: merging is then
        // how the repeat stays off the desk, and the reviewer should know that is what it is.
        if (c.getStatus() == CargoStatus.NOT_WORKABLE) sb.append(" (marked not workable)");
        return sb.toString();
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
