package com.chartering.service.parser;

import com.chartering.audit.ChangeContext;
import com.chartering.config.ParserProperties;
import com.chartering.dto.CargoRequest;
import com.chartering.dto.CompanyRequest;
import com.chartering.dto.ContactRequest;
import com.chartering.dto.IntakePasteCompanyComparison;
import com.chartering.dto.IntakePasteCompanyRequest;
import com.chartering.dto.IntakePasteCompanyResponse;
import com.chartering.dto.IntakePasteDraftResponse;
import com.chartering.dto.IntakePasteDraftResponse.CargoDraft;
import com.chartering.dto.IntakePasteDraftResponse.CompanyDraft;
import com.chartering.dto.IntakePasteDraftResponse.ContactDraft;
import com.chartering.dto.IntakePasteDraftResponse.PersonDraft;
import com.chartering.dto.IntakePasteDraftResponse.VesselDraft;
import com.chartering.dto.IntakePasteRequest;
import com.chartering.dto.PersonRequest;
import com.chartering.dto.VesselPositionRequest;
import com.chartering.dto.VesselRequest;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.Cargo;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.Person;
import com.chartering.model.Port;
import com.chartering.model.TradeArea;
import com.chartering.model.Vessel;
import com.chartering.repository.CargoRepository;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.PersonRepository;
import com.chartering.service.CargoService;
import com.chartering.service.CompanyService;
import com.chartering.service.ContactService;
import com.chartering.service.PersonService;
import com.chartering.service.QuantityTolerance;
import com.chartering.service.VesselTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Text pasted into Intake, read into drafts a person accepts one by one.
 *
 * <p><b>The difference from the mail sweep is the whole feature.</b> The sweep writes what only
 * adds and queues the rest, because nobody is watching it. Here somebody is: they pasted the
 * text a moment ago and are looking at the screen. So nothing is written on reading — not the
 * position for a hull already on file, not the cargo nothing resembles — and every part goes
 * through a form with the original text beside it. What the sweep's rules protect against, a
 * reading nobody checked, cannot happen on a screen whose only way forward is checking.
 *
 * <p>The reading itself is the sweep's, unchanged: the same client with the same prompt, the
 * same resolver for ports, areas and hulls, the same duplicate test for cargoes, the same
 * gap-fill for a vessel's particulars. Only the company block is new, because the model was
 * never taught one — see {@link CompanyStyleReader}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntakePasteService {

    private final ParserProperties props;
    private final EmailParserClient client;
    private final IntakeResolver resolver;
    private final CompanyMatcher companyMatcher;
    private final CargoRepository cargoes;
    private final CompanyRepository companies;
    private final ContactRepository contacts;
    private final PersonRepository people;
    private final CompanyService companyService;
    private final PersonService personService;
    private final ContactService contactService;
    private final ObjectMapper json;

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public IntakePasteDraftResponse read(IntakePasteRequest req) {
        requireEnabled();
        String text = req.text();

        Extraction extraction = null;
        String modelError = null;
        try {
            EmailParserClient.Completion completion = client.complete(
                    req.subject() == null ? "" : req.subject(),
                    req.receivedAt() == null ? LocalDateTime.now() : req.receivedAt(),
                    text);
            extraction = json.readValue(completion.content(), Extraction.class);
        } catch (EmailParserClient.ParserUnavailableException e) {
            modelError = "The model server did not answer, so only the company details were read: "
                    + e.getMessage();
        } catch (Exception e) {
            log.warn("Pasted text: the model's answer could not be read", e);
            modelError = "The model's answer was not the expected JSON, so only the company details were read.";
        }

        CompanyStyleReader.Style style =
                CompanyStyleReader.read(text, extraction == null ? null : extraction.broker());
        List<CompanyMatcher.Match> matches = style.isEmpty() ? List.of() : companyMatcher.match(style);
        // The firm the text is from, when that is beyond doubt — used as the broker of a cargo
        // and the reporter of a position, both of which the form still shows and lets change.
        Long sender = matches.size() == 1 && matches.get(0).strong() ? matches.get(0).companyId() : null;

        List<CargoDraft> cargoDrafts = new ArrayList<>();
        List<VesselDraft> vesselDrafts = new ArrayList<>();
        if (extraction != null) {
            List<Cargo> live = cargoes.findDuplicateCandidates(CargoService.RECOGNISED_ON_ARRIVAL_STATUSES);
            for (Extraction.ExtractedCargo c : extraction.cargoesOrEmpty()) {
                if (c.isUsable()) cargoDrafts.add(cargoDraft(c, live, sender));
            }
            for (Extraction.ExtractedVessel v : extraction.vesselsOrEmpty()) {
                if (v.isUsable()) vesselDrafts.add(vesselDraft(v, sender));
            }
        }

        return new IntakePasteDraftResponse(
                extraction == null ? null : extraction.type(),
                extraction == null ? null : Extraction.text(extraction.summary()),
                extraction != null, modelError,
                cargoDrafts, vesselDrafts,
                style.isEmpty() ? null : companyDraft(style, matches));
    }

    /** The mapping {@code IntakeService#createCargo} makes, into the form's body instead of a row. */
    private CargoDraft cargoDraft(Extraction.ExtractedCargo p, List<Cargo> live, Long sender) {
        Port loadPort = resolver.resolvePort(p.loadPort());
        TradeArea loadArea = resolver.resolveArea(p.loadArea(), p.loadPort(), loadPort);
        Port dischargePort = resolver.resolvePort(p.dischargePort());
        TradeArea dischargeArea = resolver.resolveArea(p.dischargeArea(), p.dischargePort(), dischargePort);
        Company charterer = resolver.resolveCompany(p.charterer());

        CargoRequest c = new CargoRequest();
        c.setCommodity(p.commodity().strip());
        c.setStatus("OPEN");
        c.setStowageFactor(p.stowageFactor());
        c.setQuantity(p.quantity());
        String unit = Extraction.text(p.quantityUnit());
        c.setQuantityUnit(unit == null ? "MT" : unit);
        c.setQuantityTolerance(Extraction.text(p.quantityTolerance()));
        if (p.quantityMin() != null || p.quantityMax() != null) {
            c.setQuantityMin(p.quantityMin());
            c.setQuantityMax(p.quantityMax());
        } else {
            QuantityTolerance.rangeOf(p.quantity(), Extraction.text(p.quantityTolerance())).ifPresent(r -> {
                c.setQuantityMin(r.min());
                c.setQuantityMax(r.max());
            });
        }
        c.setLoadPortId(loadPort == null ? null : loadPort.getId());
        c.setLoadPortText(Extraction.text(p.loadPort()));
        c.setLoadAreaId(loadArea == null ? null : loadArea.getId());
        c.setDischargePortId(dischargePort == null ? null : dischargePort.getId());
        c.setDischargePortText(Extraction.text(p.dischargePort()));
        c.setDischargeAreaId(dischargeArea == null ? null : dischargeArea.getId());
        c.setLaycanFrom(IntakeResolver.date(p.laycanFrom()));
        c.setLaycanTo(IntakeResolver.date(p.laycanTo()));
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
        c.setChartererCompanyId(charterer == null ? null : charterer.getId());
        c.setBrokerCompanyId(sender);

        String chartererWords = Extraction.text(p.charterer());
        String asWritten = charterer == null ? chartererWords : null;
        List<String> notes = new ArrayList<>();
        if (Extraction.text(p.notes()) != null) notes.add(Extraction.text(p.notes()));
        if (asWritten != null) notes.add("Charterer as written: " + asWritten);
        c.setNotes(notes.isEmpty() ? null : String.join("\n", notes));

        IntakePasteDraftResponse.DuplicateHint duplicate = CargoMatcher
                .findDuplicate(p, live, loadPort, loadArea)
                .map(d -> new IntakePasteDraftResponse.DuplicateHint(
                        d.cargo().getId(), d.cargo().getCommodity(), d.reasons()))
                .orElse(null);
        return new CargoDraft(c, asWritten, duplicate);
    }

    private VesselDraft vesselDraft(Extraction.ExtractedVessel v, Long sender) {
        // Her particulars by the sweep's own gap-fill onto a blank hull that is never saved:
        // that pass is what turns cbft into cubic metres and drops a capacity with no unit,
        // and a second reading of those rules here would be a second chance to get them wrong.
        Vessel blank = new Vessel();
        blank.setName(Extraction.text(v.name()));
        VesselFieldDiff.compare(blank, v);

        VesselRequest vessel = new VesselRequest();
        vessel.setName(blank.getName());
        vessel.setImoNumber(blank.getImoNumber());
        vessel.setDeadweightTonnage(blank.getDeadweightTonnage());
        vessel.setDeadweightCargoCapacity(blank.getDeadweightCargoCapacity());
        vessel.setGrainCapacityM3(blank.getGrainCapacityM3());
        vessel.setBaleCapacityM3(blank.getBaleCapacityM3());
        vessel.setMaximumDraft(blank.getMaximumDraft());
        vessel.setYearBuilt(blank.getYearBuilt());
        vessel.setVesselType(VesselTypes.canonical(v.vesselType()));
        vessel.setFlag(blank.getFlag());
        vessel.setGeared(blank.getGeared());
        vessel.setGearDescription(blank.getGearDescription());
        vessel.setHolds(blank.getHolds());
        vessel.setHatches(blank.getHatches());
        vessel.setGrainFitted(blank.getGrainFitted());
        vessel.setTimberFitted(blank.getTimberFitted());
        vessel.setImoFitted(blank.getImoFitted());
        vessel.setIceClass(blank.getIceClass());
        vessel.setNotes(Extraction.text(v.notes()));

        // The sender where the paste was matched to one firm outright, so a name this
        // correspondent has already had settled resolves here too rather than only in the sweep.
        IntakeResolver.ResolvedVessel resolved = resolver.resolveVessel(v, sender);
        IntakePasteDraftResponse.VesselMatchHint match = resolved.found()
                ? new IntakePasteDraftResponse.VesselMatchHint(resolved.vessel().getId(),
                        resolved.vessel().getName(), resolved.vessel().getImoNumber(), resolved.how().name())
                : null;
        List<IntakeResolver.Suggestion> suggestions = resolved.found() ? null : resolver.suggest(v);

        return new VesselDraft(vessel, match, suggestions, positionDraft(v, match, sender));
    }

    /** Null when the text described her without saying where she opens. */
    private VesselPositionRequest positionDraft(Extraction.ExtractedVessel v,
                                                IntakePasteDraftResponse.VesselMatchHint match,
                                                Long sender) {
        boolean said = Extraction.text(v.openPort()) != null || Extraction.text(v.openArea()) != null
                || Extraction.text(v.openFrom()) != null || Extraction.text(v.openTo()) != null
                || Extraction.text(v.openText()) != null;
        if (!said) return null;
        Port port = resolver.resolvePort(v.openPort());
        TradeArea area = resolver.resolveArea(v.openArea(), v.openPort(), port);

        VesselPositionRequest p = new VesselPositionRequest();
        p.setVesselId(match == null ? null : match.vesselId());
        p.setStatus("LIVE");
        p.setOpenPortId(port == null ? null : port.getId());
        p.setOpenPortText(Extraction.text(v.openPort()));
        p.setOpenAreaId(area == null ? null : area.getId());
        p.setOpenFrom(IntakeResolver.date(v.openFrom()));
        p.setOpenTo(IntakeResolver.date(v.openTo()));
        p.setOpenText(Extraction.text(v.openText()));
        p.setLastCargo(Extraction.text(v.lastCargo()));
        p.setCargoPreferences(Extraction.text(v.cargoPreferences()));
        p.setReportedByCompanyId(sender);
        return p;
    }

    private static CompanyDraft companyDraft(CompanyStyleReader.Style style, List<CompanyMatcher.Match> matches) {
        CompanyRequest company = new CompanyRequest();
        company.setName(style.name());
        company.setCityName(style.city());
        company.setCountry(style.country());
        company.setWebsite(style.website());
        company.setNotes(style.address());
        return new CompanyDraft(company, style.address(),
                style.people().stream().map(p -> new PersonDraft(p.fullName(), p.title(), p.jobTitle())).toList(),
                style.contacts().stream().map(c -> new ContactDraft(c.kind(), c.value(), c.label(), c.personName())).toList(),
                matches);
    }

    // ------------------------------------------------------------------ comparing with one on file

    /**
     * The pasted company set against the one on file it was matched to.
     *
     * <p>People are matched on the full name, and failing that on surname and first initial —
     * "J. Smith" on file and "John Smith" in a signature — which is only offered, never
     * applied, and says which of the two it was. Contacts are matched the way the accept skips
     * them: addresses case-insensitively, numbers on their last nine digits.
     */
    @Transactional(readOnly = true)
    public IntakePasteCompanyComparison compare(IntakePasteCompanyRequest req) {
        requireEnabled();
        if (req.getCompanyId() == null) {
            throw new IllegalArgumentException("Say which company on file to compare with.");
        }
        Company company = companies.findById(req.getCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("Company", req.getCompanyId()));

        List<IntakePasteCompanyComparison.FieldRow> fields = new ArrayList<>();
        CompanyRequest parsed = req.getCompany();
        if (parsed != null) {
            field(fields, "name", "Name", company.getName(), parsed.getName());
            field(fields, "cityName", "City", company.getCityName(), parsed.getCityName());
            field(fields, "country", "Country", company.getCountry(), parsed.getCountry());
            field(fields, "website", "Website", company.getWebsite(), parsed.getWebsite());
            String notes = blankToNull(parsed.getNotes());
            if (notes != null && (company.getNotes() == null
                    || !company.getNotes().toLowerCase(Locale.ROOT).contains(notes.toLowerCase(Locale.ROOT)))) {
                fields.add(new IntakePasteCompanyComparison.FieldRow("notes", "Notes (added below)",
                        company.getNotes(), notes));
            }
        }

        List<Person> onFile = people.findByCompanyIds(List.of(company.getId()));
        List<IntakePasteCompanyComparison.PersonRow> personRows = new ArrayList<>();
        for (IntakePasteCompanyRequest.PersonChange p : nullToEmpty(req.getPeople())) {
            personRows.add(matchPerson(p.fullName(), onFile));
        }

        Map<String, Contact> contactsOnFile = new HashMap<>();
        for (Contact c : contacts.findByCompanyIdOrderByMainDescIdAsc(company.getId())) {
            contactsOnFile.putIfAbsent(contactKey(c.getContactKind(), c.getContactValue()), c);
        }
        List<IntakePasteCompanyComparison.ContactRow> contactRows = new ArrayList<>();
        for (IntakePasteCompanyRequest.ContactChange c : nullToEmpty(req.getContacts())) {
            Contact hit = c.value() == null || c.kind() == null ? null
                    : contactsOnFile.get(contactKey(c.kind(), c.value()));
            contactRows.add(hit == null
                    ? new IntakePasteCompanyComparison.ContactRow(null, null, null)
                    : new IntakePasteCompanyComparison.ContactRow(hit.getId(), hit.getLabel(),
                            hit.getPerson() == null ? null : hit.getPerson().getFullName()));
        }

        return new IntakePasteCompanyComparison(company.getId(), company.getName(), fields,
                onFile.stream().map(p -> new IntakePasteCompanyComparison.PersonOnFile(
                        p.getId(), p.getFullName(), p.getTitle(), p.getJobTitle())).toList(),
                personRows, contactRows);
    }

    private static void field(List<IntakePasteCompanyComparison.FieldRow> rows, String field, String label,
                              String current, String parsed) {
        String read = blankToNull(parsed);
        if (read == null) return;
        if (current != null && comparable(field, current).equals(comparable(field, read))) return;
        rows.add(new IntakePasteCompanyComparison.FieldRow(field, label, current, read));
    }

    /** Websites compare without scheme or www, since the column stores a bare host. */
    private static String comparable(String field, String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        return "website".equals(field) ? v.replaceFirst("^https?://", "").replaceFirst("^www\\.", "").replaceAll("/+$", "") : v;
    }

    private static IntakePasteCompanyComparison.PersonRow matchPerson(String fullName, List<Person> onFile) {
        String name = blankToNull(fullName);
        if (name == null) return new IntakePasteCompanyComparison.PersonRow(null, null);
        for (Person p : onFile) {
            if (p.getFullName() != null && p.getFullName().strip().equalsIgnoreCase(name)) {
                return new IntakePasteCompanyComparison.PersonRow(p.getId(), "name");
            }
        }
        String[] words = name.toLowerCase(Locale.ROOT).split("[\\s.]+");
        String surname = words[words.length - 1];
        if (words.length < 2 || surname.length() < 3) return new IntakePasteCompanyComparison.PersonRow(null, null);
        Person only = null;
        for (Person p : onFile) {
            if (p.getFullName() == null) continue;
            String[] theirs = p.getFullName().strip().toLowerCase(Locale.ROOT).split("[\\s.]+");
            if (theirs.length < 2 || !theirs[theirs.length - 1].equals(surname)) continue;
            if (theirs[0].charAt(0) != words[0].charAt(0)) continue;
            if (only != null) return new IntakePasteCompanyComparison.PersonRow(null, null);
            only = p;
        }
        return only == null
                ? new IntakePasteCompanyComparison.PersonRow(null, null)
                : new IntakePasteCompanyComparison.PersonRow(only.getId(), "surname");
    }

    // ------------------------------------------------------------------ accepting a company

    /**
     * The company block as the reviewer left it: created, or added to and updated on file.
     *
     * <p><b>Only what was ticked changes.</b> The text is a signature, not a source of record,
     * so a company on file is overwritten field by field from {@code companyChanges} and nothing
     * else, a person only when an {@code existingPersonId} says which, a contact only when an
     * {@code existingContactId} does. Everything unticked only adds: people are reused by name
     * within the company and addresses already on it are skipped, so pasting the same signature
     * twice adds nothing the second time.
     *
     * <p>New records land as they would from the forms: unconfirmed, and not flagged main or for
     * circulation. Deciding who gets the circulars is a separate decision about an address, and
     * the paste has not made it.
     */
    @Transactional
    public IntakePasteCompanyResponse acceptCompany(IntakePasteCompanyRequest req) {
        requireEnabled();

        Company company = null;
        boolean created = req.getCompanyId() == null;
        if (!created) {
            company = companies.findById(req.getCompanyId())
                    .orElseThrow(() -> new ResourceNotFoundException("Company", req.getCompanyId()));
        } else if (req.getCompany() == null || blankToNull(req.getCompany().getName()) == null) {
            throw new IllegalArgumentException("Pick a company on file, or give the new one a name.");
        }
        // One change set for the firm, its people and every address, so History reads the
        // paste as the one decision it was.
        ChangeContext.describe("Pasted into Intake: "
                + (created ? req.getCompany().getName().strip() : company.getName()));

        int fieldsUpdated = 0;
        Long companyId;
        if (created) {
            companyId = companyService.create(req.getCompany()).id();
        } else {
            companyId = company.getId();
            fieldsUpdated = applyCompanyChanges(company, req.getCompanyChanges());
        }
        String companyName = created ? req.getCompany().getName().strip() : company.getName();

        // Keyed by the names the request uses, so a contact's personName finds a person whether
        // they were created here, reused by name, or matched to somebody under another spelling.
        Map<String, Long> personIds = new HashMap<>();
        Map<Long, Person> onFileById = new HashMap<>();
        for (Person p : people.findByCompanyIds(List.of(companyId))) {
            onFileById.put(p.getId(), p);
            if (p.getFullName() != null) personIds.putIfAbsent(key(p.getFullName()), p.getId());
        }
        int peopleAdded = 0;
        int peopleUpdated = 0;
        for (IntakePasteCompanyRequest.PersonChange p : nullToEmpty(req.getPeople())) {
            String fullName = blankToNull(p.fullName());
            if (p.existingPersonId() != null) {
                Person person = onFileById.get(p.existingPersonId());
                if (person == null) {
                    throw new IllegalArgumentException("That person is not on " + companyName + ".");
                }
                if (updatePerson(person, p)) peopleUpdated++;
                if (fullName != null) personIds.put(key(fullName), person.getId());
                continue;
            }
            if (fullName == null || personIds.containsKey(key(fullName))) continue;
            PersonRequest body = new PersonRequest();
            body.setFullName(fullName);
            body.setTitle(blankToNull(p.title()));
            body.setJobTitle(blankToNull(p.jobTitle()));
            body.setCompanyId(companyId);
            personIds.put(key(fullName), personService.create(body).id());
            peopleAdded++;
        }

        Map<Long, Contact> contactsById = new HashMap<>();
        Set<String> onFile = new HashSet<>();
        for (Contact c : contacts.findByCompanyIdOrderByMainDescIdAsc(companyId)) {
            contactsById.put(c.getId(), c);
            onFile.add(contactKey(c.getContactKind(), c.getContactValue()));
        }
        int contactsAdded = 0;
        int contactsUpdated = 0;
        List<String> skipped = new ArrayList<>();
        for (IntakePasteCompanyRequest.ContactChange c : nullToEmpty(req.getContacts())) {
            String value = blankToNull(c.value());
            String kind = c.kind() == null ? null : c.kind().strip().toLowerCase(Locale.ROOT);
            if (value == null || !("email".equals(kind) || "phone".equals(kind))) continue;
            String personName = blankToNull(c.personName());
            Long personId = personName == null ? null : personIds.get(key(personName));

            if (c.existingContactId() != null) {
                Contact existing = contactsById.get(c.existingContactId());
                if (existing == null) {
                    throw new IllegalArgumentException(value + " is not on " + companyName + ".");
                }
                if (updateContact(existing, c.label(), personId)) contactsUpdated++;
                continue;
            }
            if (!onFile.add(contactKey(kind, value))) {
                skipped.add(value);
                continue;
            }
            ContactRequest body = new ContactRequest();
            body.setCompanyId(companyId);
            body.setContactKind(kind);
            body.setContactValue(value);
            body.setLabel("phone".equals(kind) ? blankToNull(c.label()) : null);
            body.setPersonId(personId);
            contactService.create(body);
            contactsAdded++;
        }

        return new IntakePasteCompanyResponse(companyId, companyName, created, fieldsUpdated,
                peopleAdded, peopleUpdated, contactsAdded, contactsUpdated,
                skipped.isEmpty() ? null : skipped);
    }

    /**
     * Written straight onto the managed entity rather than through {@code CompanyService.update},
     * which takes a whole record and would clear every field the paste did not send — the
     * roles, the solo flag. The change log still has it: the audit listener reads the flush,
     * not the service.
     */
    private static int applyCompanyChanges(Company company, IntakePasteCompanyRequest.CompanyChanges changes) {
        if (changes == null) return 0;
        int n = 0;
        if (blankToNull(changes.name()) != null && !changes.name().strip().equals(company.getName())) {
            company.setName(changes.name().strip());
            n++;
        }
        if (blankToNull(changes.cityName()) != null && !changes.cityName().strip().equals(company.getCityName())) {
            company.setCityName(changes.cityName().strip());
            n++;
        }
        if (blankToNull(changes.country()) != null && !changes.country().strip().equals(company.getCountry())) {
            company.setCountry(changes.country().strip());
            n++;
        }
        if (blankToNull(changes.website()) != null && !changes.website().strip().equals(company.getWebsite())) {
            company.setWebsite(changes.website().strip());
            n++;
        }
        String notes = blankToNull(changes.notes());
        if (notes != null) {
            String current = blankToNull(company.getNotes());
            company.setNotes(current == null ? notes : current + "\n" + notes);
            n++;
        }
        return n;
    }

    /** Same reasoning as {@link #applyCompanyChanges}: the greeting and notes stay as they are. */
    private static boolean updatePerson(Person person, IntakePasteCompanyRequest.PersonChange p) {
        boolean changed = false;
        if (blankToNull(p.fullName()) != null && !p.fullName().strip().equals(person.getFullName())) {
            person.setFullName(p.fullName().strip());
            changed = true;
        }
        if (blankToNull(p.title()) != null && !p.title().strip().equals(person.getTitle())) {
            person.setTitle(p.title().strip());
            changed = true;
        }
        if (blankToNull(p.jobTitle()) != null && !p.jobTitle().strip().equals(person.getJobTitle())) {
            person.setJobTitle(p.jobTitle().strip());
            changed = true;
        }
        return changed;
    }

    /**
     * A contact on file takes the label read (phones only) and, when a person is named, belongs
     * to them. Its flags — main, circ, working — are decisions about the address and untouched.
     */
    private boolean updateContact(Contact contact, String label, Long personId) {
        boolean changed = false;
        String newLabel = blankToNull(label);
        if ("phone".equalsIgnoreCase(contact.getContactKind()) && newLabel != null
                && !newLabel.equals(contact.getLabel())) {
            contact.setLabel(newLabel);
            changed = true;
        }
        if (personId != null && (contact.getPerson() == null || !personId.equals(contact.getPerson().getId()))) {
            contact.setPerson(people.getReferenceById(personId));
            changed = true;
        }
        return changed;
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /** Emails case-insensitively, phones by their digits — "+90 212 555" is "0090212555". */
    private static String contactKey(String kind, String value) {
        if ("phone".equalsIgnoreCase(kind)) {
            String tail = CompanyMatcher.tail(value);
            return "phone:" + (tail != null ? tail : value.replaceAll("\\D", ""));
        }
        return kind.toLowerCase(Locale.ROOT) + ":" + value.strip().toLowerCase(Locale.ROOT);
    }

    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new FeatureDisabledException(
                    "The email parser is not enabled on this deployment (PARSER_ENABLED).");
        }
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
