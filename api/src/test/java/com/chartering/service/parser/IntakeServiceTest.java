package com.chartering.service.parser;

import com.chartering.model.*;
import com.chartering.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a parse writes on its own, and what it refuses to.
 *
 * <p>Stubbed repositories rather than a database: every decision under test is a judgement
 * about a reading — is this the same position we were already told about, does this hull
 * disagree with the record — and none of it depends on JPA.
 *
 * <p>The re-confirmation rule gets most of the attention here because it is the one that
 * shapes what the Open Fleet tab and a vessel's history become over months. A broker's list
 * arrives every morning and most of it is yesterday's list again; twinning every repeat
 * would turn "she has opened in the Adriatic five times this year" into a count of Mondays.
 */
class IntakeServiceTest {

    private IntakeItemRepository items;
    private CargoSourceRepository cargoSources;
    private CargoRepository cargoes;
    private VesselRepository vessels;
    private VesselExNameRepository exNames;
    private VesselPositionRepository positions;
    private IntakeResolver resolver;
    private com.chartering.repository.IntakeItemSourceRepository itemSources;
    private com.chartering.service.lookup.VesselLookupService lookupService;
    private IntakeService service;

    private Vessel pacificDawn;
    private Company interscan;
    private MailMessage message;
    private ParsedEmail parsed;
    private List<VesselPosition> onFile;

    @BeforeEach
    void setUp() {
        items = mock(IntakeItemRepository.class);
        cargoSources = mock(CargoSourceRepository.class);
        cargoes = mock(CargoRepository.class);
        vessels = mock(VesselRepository.class);
        exNames = mock(VesselExNameRepository.class);
        positions = mock(VesselPositionRepository.class);
        resolver = mock(IntakeResolver.class);
        itemSources = mock(com.chartering.repository.IntakeItemSourceRepository.class);
        lookupService = mock(com.chartering.service.lookup.VesselLookupService.class);
        service = new IntakeService(items, cargoSources, cargoes, vessels, exNames, itemSources,
                positions, resolver, lookupService,
                mock(com.chartering.service.VesselService.class), new ObjectMapper());
        // The item is saved and then a source row is attached to it, so the mock has to hand
        // the entity back rather than null.
        when(items.save(any(IntakeItem.class))).thenAnswer(i -> i.getArgument(0));

        pacificDawn = new Vessel();
        pacificDawn.setId(42L);
        pacificDawn.setName("PACIFIC DAWN");

        interscan = new Company();
        interscan.setId(3L);
        interscan.setName("Interscan");

        message = new MailMessage();
        message.setId(100L);
        message.setSubject("Open tonnage");
        message.setCompany(interscan);
        message.setSentAt(LocalDateTime.of(2026, 9, 4, 8, 30));
        message.setReceivedAt(LocalDateTime.of(2026, 9, 4, 8, 31));

        parsed = new ParsedEmail();
        parsed.setId(9L);
        parsed.setMailMessage(message);

        onFile = new ArrayList<>();
        when(positions.findByVesselIdOrderByReportedAtDesc(anyLong())).thenReturn(onFile);
        when(items.pendingForVessel(any(), anyLong())).thenReturn(List.of());
        when(items.pendingNewVessel(any())).thenReturn(List.of());
        when(resolver.resolvePort(any())).thenReturn(null);
        when(resolver.resolveArea(any(), any(), any())).thenReturn(null);
        when(resolver.suggest(any())).thenReturn(List.of());
    }

    private static Extraction.ExtractedVessel opening(String name, String openText,
                                                      String from, String to) {
        return new Extraction.ExtractedVessel(
                name, "", "", null, null, null, null, "",
                null, null, "", null, "", null, null, null, null, null, "",
                "MARMARA", "", from == null ? "" : from, to == null ? "" : to,
                openText, "", "", "");
    }

    private static Extraction positionEmail(Extraction.ExtractedVessel... vs) {
        return new Extraction("vessel_opening", List.of(), List.of(vs), null, null);
    }

    private VesselPosition live(Company reporter, LocalDate from, LocalDate to,
                                String openText, OffsetDateTime reportedAt) {
        VesselPosition p = new VesselPosition();
        p.setId(500L);
        p.setVessel(pacificDawn);
        p.setStatus(PositionStatus.LIVE);
        p.setOpenPortText("MARMARA");
        p.setOpenFrom(from);
        p.setOpenTo(to);
        p.setOpenText(openText);
        p.setReportedByCompany(reporter);
        p.setReportedAt(reportedAt);
        return p;
    }

    @Test
    void filesAPositionForAHullAlreadyOnFileWithoutAsking() {
        when(resolver.resolveVessel(any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));

        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("PACIFIC DAWN", "1/3 SEPT", "2026-09-01", "2026-09-03")));

        assertThat(outcome.positionsApplied()).isEqualTo(1);
        assertThat(outcome.itemsRaised()).isZero();

        ArgumentCaptor<VesselPosition> saved = ArgumentCaptor.forClass(VesselPosition.class);
        verify(positions).save(saved.capture());
        assertThat(saved.getValue().getVessel()).isSameAs(pacificDawn);
        assertThat(saved.getValue().getOpenFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(saved.getValue().isFromMail()).isTrue();
        assertThat(saved.getValue().getReportedByCompany()).isSameAs(interscan);
        // The sender's own clock, not ours: a list read out of a three-day-old email is
        // three days old, and staleness is the first thing Open Fleet has to show.
        assertThat(saved.getValue().getReportedAt().getHour()).isEqualTo(8);
    }

    @Test
    void treatsAnIdenticalRepeatFromTheSameBrokerAsAReConfirmation() {
        when(resolver.resolveVessel(any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));
        // Told on the 3rd; the email under test was sent on the 4th.
        VesselPosition yesterday = live(interscan, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3),
                "1/3 SEPT", OffsetDateTime.parse("2026-09-03T08:30:00+02:00"));
        onFile.add(yesterday);

        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("PACIFIC DAWN", "1/3 SEPT", "2026-09-01", "2026-09-03")));

        // No twin row, and the existing one is still live rather than superseded by itself.
        verify(positions, never()).save(any());
        assertThat(outcome.positionsApplied()).isZero();
        assertThat(yesterday.getStatus()).isEqualTo(PositionStatus.LIVE);
        // What did change is when we last heard it, which is what that column means.
        assertThat(yesterday.getReportedAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(yesterday.getSourceMailMessage()).isSameAs(message);
    }

    @Test
    void neverDragsAFresherReadingBackwardsWithAnOlderEmail() {
        when(resolver.resolveVessel(any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));
        // Already told on the 6th; the email being read was sent on the 4th, which happens
        // whenever a backlog is swept or an old thread is re-synced.
        OffsetDateTime fresher = OffsetDateTime.parse("2026-09-06T08:30:00+02:00");
        VesselPosition known = live(interscan, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3),
                "1/3 SEPT", fresher);
        onFile.add(known);

        service.apply(parsed,
                positionEmail(opening("PACIFIC DAWN", "1/3 SEPT", "2026-09-01", "2026-09-03")));

        verify(positions, never()).save(any());
        // Untouched: making it look staler than it is would be worse than doing nothing.
        assertThat(known.getReportedAt()).isEqualTo(fresher);
    }

    @Test
    void recordsANewRowAndSupersedesTheSameBrokersPreviousOneWhenTheDatesMove() {
        when(resolver.resolveVessel(any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));
        VesselPosition yesterday = live(interscan, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3),
                "1/3 SEPT", OffsetDateTime.now().minusDays(1));
        onFile.add(yesterday);

        service.apply(parsed,
                positionEmail(opening("PACIFIC DAWN", "5/7 SEPT", "2026-09-05", "2026-09-07")));

        verify(positions).save(any(VesselPosition.class));
        assertThat(yesterday.getStatus()).isEqualTo(PositionStatus.SUPERSEDED);
    }

    @Test
    void leavesAnotherBrokersDisagreeingPositionAlone() {
        when(resolver.resolveVessel(any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));
        Company gn = new Company();
        gn.setId(88L);
        gn.setName("GN Shipping");
        VesselPosition theirs = live(gn, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3),
                "1/3 SEPT", OffsetDateTime.now().minusDays(1));
        onFile.add(theirs);

        service.apply(parsed,
                positionEmail(opening("PACIFIC DAWN", "5/7 SEPT", "2026-09-05", "2026-09-07")));

        // Two brokers disagreeing is the record, not a conflict to resolve.
        verify(positions).save(any(VesselPosition.class));
        assertThat(theirs.getStatus()).isEqualTo(PositionStatus.LIVE);
    }

    @Test
    void raisesAnItemForAHullNothingOnFileAnswersTo() {
        when(resolver.resolveVessel(any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(null, IntakeResolver.VesselMatch.NONE));

        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("UNKNOWN TRADER", "SPOT", null, null)));

        assertThat(outcome.itemsRaised()).isEqualTo(1);
        assertThat(outcome.positionsApplied()).isZero();
        // Nothing was created and nothing was filed — that is the whole point of the item.
        verify(vessels, never()).save(any());
        verify(positions, never()).save(any());

        ArgumentCaptor<IntakeItem> raised = ArgumentCaptor.forClass(IntakeItem.class);
        verify(items).save(raised.capture());
        assertThat(raised.getValue().getKind()).isEqualTo(IntakeItemKind.NEW_VESSEL);
        assertThat(raised.getValue().getSubjectLabel()).isEqualTo("UNKNOWN TRADER");
    }

    @Test
    void filesThePositionAndRaisesTheDisagreementSeparately() {
        pacificDawn.setDeadweightTonnage(new BigDecimal("28500"));
        when(resolver.resolveVessel(any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));

        Extraction.ExtractedVessel v = new Extraction.ExtractedVessel(
                "PACIFIC DAWN", "", "", new BigDecimal("32000"), null, null, null, "",
                null, null, "", null, "", null, null, null, null, null, "",
                "MARMARA", "", "2026-09-01", "2026-09-03", "1/3 SEPT", "", "", "");

        IntakeService.ApplyOutcome outcome = service.apply(parsed, positionEmail(v));

        // Where she is open is the perishable half and lands at once; what she is can wait.
        assertThat(outcome.positionsApplied()).isEqualTo(1);
        assertThat(outcome.itemsRaised()).isEqualTo(1);
        assertThat(pacificDawn.getDeadweightTonnage()).isEqualByComparingTo("28500");

        ArgumentCaptor<IntakeItem> raised = ArgumentCaptor.forClass(IntakeItem.class);
        verify(items).save(raised.capture());
        assertThat(raised.getValue().getKind()).isEqualTo(IntakeItemKind.VESSEL_FIELDS);
        assertThat(raised.getValue().getVesselId()).isEqualTo(42L);
    }

    @Test
    void mergesASecondEmailIntoTheQuestionAlreadyWaiting() {
        pacificDawn.setDeadweightTonnage(new BigDecimal("28500"));
        when(resolver.resolveVessel(any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));

        IntakeItem alreadyAsked = new IntakeItem();
        alreadyAsked.setId(1L);
        alreadyAsked.setKind(IntakeItemKind.VESSEL_FIELDS);
        alreadyAsked.setPayload("""
                {"vesselId":42,"vesselName":"PACIFIC DAWN","diffs":[
                  {"field":"deadweightTonnage","label":"DWT","current":"28500 t","incoming":"32000 t"}],
                 "filled":[]}""");
        when(items.pendingForVessel(IntakeItemKind.VESSEL_FIELDS, 42L))
                .thenReturn(List.of(alreadyAsked));

        Extraction.ExtractedVessel v = new Extraction.ExtractedVessel(
                "PACIFIC DAWN", "", "", new BigDecimal("32000"), null, null, null, "",
                null, null, "", null, "", null, null, null, null, null, "",
                "MARMARA", "", "2026-09-01", "2026-09-03", "1/3 SEPT", "", "", "");

        IntakeService.ApplyOutcome outcome = service.apply(parsed, positionEmail(v));

        // The list arrives every morning saying the same thing. Thirty copies of one
        // unanswered question is a queue that stops being opened.
        assertThat(outcome.itemsRaised()).isZero();
        // The waiting item is updated rather than a second one created, and this email is
        // recorded against it - which is what makes both originals readable from the one row.
        verify(items).save(alreadyAsked);
        verify(itemSources).save(any());
        assertThat(alreadyAsked.getPayload()).contains("deadweightTonnage");
        // The position still lands, because that is a different fact.
        assertThat(outcome.positionsApplied()).isEqualTo(1);
    }

    /**
     * The case that made this necessary. FOX arrived twice from one broker, every figure
     * identical except a reworded type - "GENERAL-DRY CARGO VESSEL / DOUBLE SKIN/BOX" against
     * "GENERAL-DRY CARGO VESSEL" - and the exact suppression that used to guard this let the
     * second one through as a separate question about the same hull.
     */
    @Test
    void mergesEvenWhenTheSecondEmailWordsAFigureDifferently() {
        pacificDawn.setVesselType("SEA TYPE BOX SHAPE");
        when(resolver.resolveVessel(any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));

        IntakeItem alreadyAsked = new IntakeItem();
        alreadyAsked.setId(1L);
        alreadyAsked.setKind(IntakeItemKind.VESSEL_FIELDS);
        alreadyAsked.setPayload("""
                {"vesselId":42,"vesselName":"PACIFIC DAWN","diffs":[
                  {"field":"vesselType","label":"Type","current":"SEA TYPE BOX SHAPE",
                   "incoming":"GENERAL-DRY CARGO VESSEL / DOUBLE SKIN/BOX"}],
                 "filled":[]}""");
        when(items.pendingForVessel(IntakeItemKind.VESSEL_FIELDS, 42L))
                .thenReturn(List.of(alreadyAsked));

        Extraction.ExtractedVessel v = new Extraction.ExtractedVessel(
                "PACIFIC DAWN", "", "GENERAL-DRY CARGO VESSEL", null, null, null, null, "",
                null, null, "", null, "", null, null, null, null, null, "",
                "MARMARA", "", "2026-09-01", "2026-09-03", "1/3 SEPT", "", "", "");

        IntakeService.ApplyOutcome outcome = service.apply(parsed, positionEmail(v));

        assertThat(outcome.itemsRaised()).isZero();
        verify(items, never()).save(argThat(i -> i.getId() == null));
        // The newer wording wins the figure: the later list is the later statement, and both
        // emails stay readable from the item for anybody who wants to compare them.
        assertThat(alreadyAsked.getPayload()).contains("GENERAL-DRY CARGO VESSEL");
        assertThat(alreadyAsked.getPayload()).doesNotContain("DOUBLE SKIN");
    }

    /**
     * An IMO is identity. A hull whose looked-up number is already on a ship here is that
     * ship under a name nobody recognised — so the question stops being "should we create
     * her" and becomes "her particulars disagree", which is a question we know how to ask.
     */
    @Test
    void turnsANewVesselIntoAParticularsReviewWhenTheNumberIsAlreadyOnFile() {
        Vessel celia = new Vessel();
        celia.setId(2776L);
        celia.setName("CELIA");
        celia.setFlag("Malta");

        IntakeItem newVessel = new IntakeItem();
        newVessel.setId(6L);
        newVessel.setKind(IntakeItemKind.NEW_VESSEL);
        newVessel.setParsedEmail(parsed);
        newVessel.setSubjectLabel("LIUDMILA");
        newVessel.setPayload("""
                {"vessel":{"name":"LIUDMILA","flag":"PANAMA","openArea":"MARMARA"},
                 "searchedBy":"LIUDMILA","suggestions":[]}""");
        when(items.pendingByKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(newVessel));

        VesselLookup row = new VesselLookup();
        row.setStatus(VesselLookup.STATUS_OK);
        row.setMatchedImo("9344394");
        when(lookupService.forItem(6L)).thenReturn(java.util.Optional.of(row));
        when(lookupService.alreadyOnFile(row)).thenReturn(java.util.Optional.of(celia));

        assertThat(service.reconcileIdentifiedHulls()).isEqualTo(1);

        assertThat(newVessel.getKind()).isEqualTo(IntakeItemKind.VESSEL_FIELDS);
        assertThat(newVessel.getVesselId()).isEqualTo(2776L);
        // The label becomes the ship we hold, because that is the record being asked about.
        assertThat(newVessel.getSubjectLabel()).isEqualTo("CELIA");
        // Says where the identification came from, so the drawer can show the lookup's own
        // confidence beside it - the IMO step is certain, the search behind it may not be.
        assertThat(newVessel.getPayload()).contains("LOOKUP_IMO");
        // The rename is an ordinary row for a person to accept, not something done to her.
        assertThat(newVessel.getPayload()).contains("LIUDMILA");
        verify(vessels, never()).save(any());
        // Her position is filed, because that is an add and it is the half that goes stale.
        verify(positions).save(any());
    }

    @Test
    void leavesANewVesselAloneWhenTheNumberIsOnNoHullHere() {
        IntakeItem newVessel = new IntakeItem();
        newVessel.setId(7L);
        newVessel.setKind(IntakeItemKind.NEW_VESSEL);
        newVessel.setParsedEmail(parsed);
        newVessel.setPayload("""
                {"vessel":{"name":"UNKNOWN TRADER"},"searchedBy":"UNKNOWN TRADER","suggestions":[]}""");
        when(items.pendingByKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(newVessel));

        VesselLookup row = new VesselLookup();
        row.setStatus(VesselLookup.STATUS_OK);
        row.setMatchedImo("9999999");
        when(lookupService.forItem(7L)).thenReturn(java.util.Optional.of(row));
        when(lookupService.alreadyOnFile(row)).thenReturn(java.util.Optional.empty());

        assertThat(service.reconcileIdentifiedHulls()).isZero();
        assertThat(newVessel.getKind()).isEqualTo(IntakeItemKind.NEW_VESSEL);
        verify(positions, never()).save(any());
    }

    /** Nothing has been searched for her yet, so there is no number to be identity about. */
    @Test
    void leavesANewVesselAloneWhenNothingHasBeenLookedUp() {
        IntakeItem newVessel = new IntakeItem();
        newVessel.setId(8L);
        newVessel.setKind(IntakeItemKind.NEW_VESSEL);
        newVessel.setParsedEmail(parsed);
        newVessel.setPayload("""
                {"vessel":{"name":"UNKNOWN TRADER"},"searchedBy":"UNKNOWN TRADER","suggestions":[]}""");
        when(items.pendingByKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(newVessel));
        when(lookupService.forItem(8L)).thenReturn(java.util.Optional.empty());

        assertThat(service.reconcileIdentifiedHulls()).isZero();
        assertThat(newVessel.getKind()).isEqualTo(IntakeItemKind.NEW_VESSEL);
    }

    /**
     * Accepting a rename has to keep the name she is losing. It is the name this database has
     * been finding her under, and a circular arriving next week still uses it — FWN SOLIDE
     * became LADY VIOLETTA with the rename in the change log and no former name anywhere,
     * because the guard compared the name being filed against itself.
     */
    @Test
    void filesTheNameSheIsLosingWhenARenameIsAccepted() {
        Vessel solide = new Vessel();
        solide.setId(2692L);
        solide.setName("FWN SOLIDE");
        when(vessels.findById(2692L)).thenReturn(java.util.Optional.of(solide));

        IntakeItem item = new IntakeItem();
        item.setId(165L);
        item.setKind(IntakeItemKind.VESSEL_FIELDS);
        item.setStatus(IntakeItemStatus.PENDING);
        item.setParsedEmail(parsed);
        item.setPayload("""
                {"vesselId":2692,"vesselName":"FWN SOLIDE","matchedBy":"LOOKUP_IMO",
                 "vessel":{"name":"LADY VIOLETTA"},
                 "diffs":[{"field":"name","label":"Name","current":"FWN SOLIDE",
                           "incoming":"LADY VIOLETTA"}],
                 "filled":[]}""");
        when(items.findWithEmailById(165L)).thenReturn(java.util.Optional.of(item));

        service.resolve(165L, IntakeService.Action.ACCEPT, List.of("name"), null, null, "me");

        ArgumentCaptor<VesselExName> filed = ArgumentCaptor.forClass(VesselExName.class);
        verify(exNames).save(filed.capture());
        assertThat(filed.getValue().getName()).isEqualTo("FWN SOLIDE");
        assertThat(solide.getName()).isEqualTo("LADY VIOLETTA");
    }

    /** Accepting every field is what "Accept all" sends as an empty list, and it renames too. */
    @Test
    void filesTheNameSheIsLosingWhenEveryFieldIsAccepted() {
        Vessel solide = new Vessel();
        solide.setId(2692L);
        solide.setName("FWN SOLIDE");
        when(vessels.findById(2692L)).thenReturn(java.util.Optional.of(solide));

        IntakeItem item = new IntakeItem();
        item.setId(165L);
        item.setKind(IntakeItemKind.VESSEL_FIELDS);
        item.setStatus(IntakeItemStatus.PENDING);
        item.setParsedEmail(parsed);
        item.setPayload("""
                {"vesselId":2692,"vesselName":"FWN SOLIDE","matchedBy":"LOOKUP_IMO",
                 "vessel":{"name":"LADY VIOLETTA"},
                 "diffs":[{"field":"name","label":"Name","current":"FWN SOLIDE",
                           "incoming":"LADY VIOLETTA"}],
                 "filled":[]}""");
        when(items.findWithEmailById(165L)).thenReturn(java.util.Optional.of(item));

        service.resolve(165L, IntakeService.Action.ACCEPT, List.of(), null, null, "me");

        verify(exNames).save(any());
    }

    /**
     * A spelling correction is not a rename. Filing the old spelling would leave a former name
     * nobody ever called her, and the name search would match it for ever.
     */
    @Test
    void doesNotFileAFormerNameWhenOnlyTheSpellingChanged() {
        Vessel hull = new Vessel();
        hull.setId(1424L);
        hull.setName("HACI HILMI II");
        when(vessels.findById(1424L)).thenReturn(java.util.Optional.of(hull));

        IntakeItem item = new IntakeItem();
        item.setId(96L);
        item.setKind(IntakeItemKind.VESSEL_FIELDS);
        item.setStatus(IntakeItemStatus.PENDING);
        item.setParsedEmail(parsed);
        item.setPayload("""
                {"vesselId":1424,"vesselName":"HACI HILMI II","matchedBy":"LOOKUP_IMO",
                 "vessel":{"name":"haci hilmi ii"},
                 "diffs":[{"field":"name","label":"Name","current":"HACI HILMI II",
                           "incoming":"haci hilmi ii"}],
                 "filled":[]}""");
        when(items.findWithEmailById(96L)).thenReturn(java.util.Optional.of(item));

        service.resolve(96L, IntakeService.Action.ACCEPT, List.of("name"), null, null, "me");

        verify(exNames, never()).save(any());
    }

    @Test
    void ignoresAVesselTheModelReturnedWithNoName() {
        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("", "SPOT", null, null)));

        assertThat(outcome.positionsApplied()).isZero();
        assertThat(outcome.itemsRaised()).isZero();
        verify(items, never()).save(any());
    }
}
