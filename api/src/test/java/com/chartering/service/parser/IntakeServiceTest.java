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
import static org.mockito.Mockito.atLeastOnce;
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
    private CompanyStyleIntake styles;
    private com.chartering.service.SettingsService settingsService;
    private com.chartering.service.lookup.VesselLookupService lookupService;
    private IntakeService service;

    private Vessel pacificDawn;
    private Company interscan;
    private MailMessage message;
    private ParsedEmail parsed;
    private List<VesselPosition> onFile;
    private com.chartering.repository.IntakeFieldDecisionRepository decisions;
    private com.chartering.repository.IntakeVesselAliasRepository aliases;

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
        decisions = mock(com.chartering.repository.IntakeFieldDecisionRepository.class);
        aliases = mock(com.chartering.repository.IntakeVesselAliasRepository.class);
        when(decisions.forVessel(any())).thenReturn(List.of());
        when(aliases.find(any(), any())).thenReturn(java.util.Optional.empty());
        styles = mock(CompanyStyleIntake.class);
        settingsService = mock(com.chartering.service.SettingsService.class);
        service = new IntakeService(items, cargoSources, cargoes, vessels, exNames, itemSources,
                decisions, aliases, positions, resolver,
                styles,
                new com.chartering.config.ParserProperties(),
                mock(com.chartering.repository.CompanyRepository.class),
                mock(IntakePasteService.class),
                mock(com.chartering.repository.PersonRepository.class),
                settingsService,
                lookupService,
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
        when(items.pendingForCompany(anyLong())).thenReturn(List.of());
        when(items.pendingNewCompany(any())).thenReturn(List.of());
        when(settingsService.ownAddresses()).thenReturn(java.util.Set.of());
    }

    // ---------------------------------------------------------- the firm that signed it

    /** A question about a firm on file, as CompanyStyleIntake would hand one over. */
    private void signatureRaises(Long companyId, String name, String hash) {
        com.chartering.dto.CompanyRequest company = new com.chartering.dto.CompanyRequest();
        company.setName(name);
        IntakePayloads.CompanyDetails payload = new IntakePayloads.CompanyDetails(
                new com.chartering.dto.IntakePasteDraftResponse.CompanyDraft(
                        company, null, List.of(), List.of(), List.of()),
                companyId, companyId == null ? null : name, "email",
                List.of("1 address not on file"), hash);
        when(styles.question(any())).thenReturn(java.util.Optional.of(payload));
    }

    private static Extraction emptyEmail() {
        return new Extraction("other", List.of(), List.of(), null, null);
    }

    @Test
    void raisesTheFirmThatSignedWhenTheRecordDisagrees() {
        signatureRaises(3L, "Interscan", "hash-a");

        IntakeService.ApplyOutcome outcome = service.apply(parsed, emptyEmail());

        assertThat(outcome.itemsRaised()).isEqualTo(1);
        ArgumentCaptor<IntakeItem> saved = ArgumentCaptor.forClass(IntakeItem.class);
        verify(items, atLeastOnce()).save(saved.capture());
        IntakeItem item = saved.getAllValues().get(0);
        assertThat(item.getKind()).isEqualTo(IntakeItemKind.COMPANY_DETAILS);
        assertThat(item.getSubjectLabel()).isEqualTo("Interscan");
        assertThat(item.getCompanyId()).isEqualTo(3L);
    }

    @Test
    void doesNotAskTwiceAboutOneFirm() {
        // A broker signs every list he sends, not only when something has changed. Without
        // this the queue would carry a row per circular per firm and would stop being read -
        // the failure the vessel rule already prevents, at ten times the rate.
        signatureRaises(3L, "Interscan", "hash-a");
        IntakeItem waiting = new IntakeItem();
        waiting.setId(77L);
        waiting.setKind(IntakeItemKind.COMPANY_DETAILS);
        waiting.setCompanyId(3L);
        waiting.setPayload("{}");
        when(items.pendingForCompany(3L)).thenReturn(List.of(waiting));

        IntakeService.ApplyOutcome outcome = service.apply(parsed, emptyEmail());

        // Not a new question, so the queue is no longer than it was - but the newer reading
        // wins the payload, and this arrival is recorded against the item that was waiting.
        assertThat(outcome.itemsRaised()).isZero();
        assertThat(waiting.getPayload()).contains("Interscan");
        verify(itemSources).save(any());
    }

    @Test
    void doesNotAskAgainAboutASignatureAlreadyTurnedDown() {
        // "Do not file these details" would be worthless if the same broker's next list undid
        // it. Only a signature that has actually moved comes back.
        signatureRaises(3L, "Interscan", "hash-a");
        when(items.countRejectedWithStyle("hash-a")).thenReturn(1L);

        assertThat(service.apply(parsed, emptyEmail()).itemsRaised()).isZero();
        verify(items, never()).save(any(IntakeItem.class));
    }

    @Test
    void neverProposesTheDesksOwnSignature() {
        // The sweep reads the Sent folder as well as the inbox, so our own replies arrive
        // carrying our own block. A queue asking whether to create the firm you work for is a
        // queue with an obvious bug in it.
        signatureRaises(null, "Our Own Desk", "hash-b");
        message.setFromAddress("chartering@ourdesk.example");
        when(settingsService.ownAddresses())
                .thenReturn(java.util.Set.of("chartering@ourdesk.example"));

        assertThat(service.apply(parsed, emptyEmail()).itemsRaised()).isZero();
        verify(items, never()).save(any(IntakeItem.class));
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
        when(resolver.resolveVessel(any(), any()))
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
        when(resolver.resolveVessel(any(), any()))
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
        when(resolver.resolveVessel(any(), any()))
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
        when(resolver.resolveVessel(any(), any()))
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
        when(resolver.resolveVessel(any(), any()))
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
        when(resolver.resolveVessel(any(), any()))
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
        when(resolver.resolveVessel(any(), any()))
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

    /**
     * The JELENA repeat: a figure this firm has already had turned down is not a question.
     *
     * <p>ANGORA re-sends the same list every morning. The reviewer answered its reading of her
     * bale once; nothing recorded that, so the identical email raised the identical item four
     * days running. The decision is what makes the fifth morning quiet.
     */
    @Test
    void doesNotRaiseAValueThisSenderHasAlreadyHadTurnedDown() {
        pacificDawn.setDeadweightTonnage(new BigDecimal("28500"));
        when(resolver.resolveVessel(any(), any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));
        when(decisions.forVessel(42L)).thenReturn(List.of(
                settled(interscan, "deadweightTonnage", "32000")));

        IntakeService.ApplyOutcome outcome = service.apply(parsed, positionEmail(disagreeing()));

        // The position still lands - it is an add, and it is the half the desk is waiting for.
        assertThat(outcome.positionsApplied()).isEqualTo(1);
        assertThat(outcome.itemsRaised()).isZero();
        verify(items, never()).save(any());
    }

    /**
     * A second firm saying the same thing is a second opinion, and it is still asked.
     *
     * <p>That one broker was wrong about her deadweight says nothing about the next one, and
     * suppressing the second would hide exactly the corroboration that would settle the
     * question the other way.
     */
    @Test
    void stillAsksWhenADifferentFirmReportsTheSameFigure() {
        pacificDawn.setDeadweightTonnage(new BigDecimal("28500"));
        when(resolver.resolveVessel(any(), any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));

        Company someoneElse = new Company();
        someoneElse.setId(999L);
        someoneElse.setName("ANGORA");
        when(decisions.forVessel(42L)).thenReturn(List.of(
                settled(someoneElse, "deadweightTonnage", "32000")));

        IntakeService.ApplyOutcome outcome = service.apply(parsed, positionEmail(disagreeing()));

        assertThat(outcome.itemsRaised()).isEqualTo(1);
    }

    /** She disagrees about her deadweight and says where she is open. */
    private static Extraction.ExtractedVessel disagreeing() {
        return new Extraction.ExtractedVessel(
                "PACIFIC DAWN", "", "", new BigDecimal("32000"), null, null, null, "",
                null, null, "", null, "", null, null, null, null, null, "",
                "MARMARA", "", "2026-09-01", "2026-09-03", "1/3 SEPT", "", "", "");
    }

    private static com.chartering.model.IntakeFieldDecision settled(Company who, String field,
                                                                    String value) {
        com.chartering.model.IntakeFieldDecision d = new com.chartering.model.IntakeFieldDecision();
        d.setVesselId(42L);
        d.setField(field);
        d.setReportedByCompany(who);
        d.setValueText(value);
        d.setDecision(com.chartering.model.IntakeFieldDecision.KEPT);
        return d;
    }

    @Test
    void mergesASecondEmailIntoTheQuestionAlreadyWaiting() {
        pacificDawn.setDeadweightTonnage(new BigDecimal("28500"));
        when(resolver.resolveVessel(any(), any()))
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
     * identical except one reworded description, and the exact suppression that used to guard
     * this let the second one through as a separate question about the same hull. The field
     * was her type then; type is no longer compared, so the same shape is tested on the gear.
     *
     * <p>The stored question also carries a type row from before that change, and the merge
     * drops it rather than carrying a field nobody is asked about into the merged question.
     */
    @Test
    void mergesEvenWhenTheSecondEmailWordsAFigureDifferently() {
        pacificDawn.setGearDescription("2x30T CRANES");
        when(resolver.resolveVessel(any(), any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));

        IntakeItem alreadyAsked = new IntakeItem();
        alreadyAsked.setId(1L);
        alreadyAsked.setKind(IntakeItemKind.VESSEL_FIELDS);
        alreadyAsked.setPayload("""
                {"vesselId":42,"vesselName":"PACIFIC DAWN","diffs":[
                  {"field":"gearDescription","label":"Gear","current":"2x30T CRANES",
                   "incoming":"2 X 25T CRANES, GRABS 2X6CBM"},
                  {"field":"vesselType","label":"Type","current":"SEA TYPE BOX SHAPE",
                   "incoming":"GENERAL-DRY CARGO VESSEL / DOUBLE SKIN/BOX"}],
                 "filled":[]}""");
        when(items.pendingForVessel(IntakeItemKind.VESSEL_FIELDS, 42L))
                .thenReturn(List.of(alreadyAsked));

        Extraction.ExtractedVessel v = new Extraction.ExtractedVessel(
                "PACIFIC DAWN", "", "GENERAL-DRY CARGO VESSEL", null, null, null, null, "",
                null, null, "", null, "2 X 25 T CRANES", null, null, null, null, null, "",
                "MARMARA", "", "2026-09-01", "2026-09-03", "1/3 SEPT", "", "", "");

        IntakeService.ApplyOutcome outcome = service.apply(parsed, positionEmail(v));

        assertThat(outcome.itemsRaised()).isZero();
        verify(items, never()).save(argThat(i -> i.getId() == null));
        // The newer wording wins the figure: the later list is the later statement, and both
        // emails stay readable from the item for anybody who wants to compare them.
        assertThat(alreadyAsked.getPayload()).contains("2 X 25 T CRANES");
        assertThat(alreadyAsked.getPayload()).doesNotContain("GRABS");
        // The type row from before type stopped being compared is gone.
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

        service.resolve(165L, IntakeService.Action.ACCEPT, List.of("name"), null, null, null, "me");

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

        service.resolve(165L, IntakeService.Action.ACCEPT, List.of(), null, null, null, "me");

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

        service.resolve(96L, IntakeService.Action.ACCEPT, List.of("name"), null, null, null, "me");

        verify(exNames, never()).save(any());
    }

    // ------------------------------------------- what a manual link teaches the resolver

    /**
     * Linking a hull by hand is what stops the same list asking again next week.
     *
     * <p>A NEW_VESSEL item exists precisely because nothing here matched: the email names a
     * hull under a name no row carries, or under one two rows carry. Answering it points the
     * reading at a ship, and the answer has to be written somewhere a later parse will read,
     * or the identical list raises the identical question every time it arrives.
     *
     * <p>Two things are written, and they answer different questions. The former name is a
     * fact about the <em>ship</em> and is what {@code findByExactName} finds for anybody; the
     * alias is a fact about one <em>firm's vocabulary</em> and is what settles a name two
     * hulls share, which no former name can. See {@code IntakeVesselAlias}.
     */
    @Test
    void aManuallyLinkedHullIsRecognisedFromThatFirmAfterwards() {
        when(vessels.findById(42L)).thenReturn(java.util.Optional.of(pacificDawn));
        IntakeItem item = newVesselItem(600L, "ALPHA");
        when(items.findWithEmailById(600L)).thenReturn(java.util.Optional.of(item));
        IntakeItemSource arrival = new IntakeItemSource();
        arrival.setIntakeItem(item);
        arrival.setParsedEmail(parsed);
        arrival.setReportedByCompany(interscan);
        when(itemSources.forItem(600L)).thenReturn(List.of(arrival));

        service.resolve(600L, IntakeService.Action.ALTERNATIVE, null, null, 42L, null, "me");

        ArgumentCaptor<com.chartering.model.IntakeVesselAlias> saved =
                ArgumentCaptor.forClass(com.chartering.model.IntakeVesselAlias.class);
        verify(aliases).save(saved.capture());
        assertThat(saved.getValue().getVesselId()).isEqualTo(42L);
        assertThat(saved.getValue().getNameKey()).isEqualTo("alpha");
        assertThat(saved.getValue().getReportedByCompany()).isSameAs(interscan);
        assertThat(saved.getValue().getSource())
                .isEqualTo(com.chartering.model.IntakeVesselAlias.LINKED);
        // And the name itself, which any firm's list can now find her by.
        verify(exNames).save(argThat(e -> e.getName().equals("ALPHA")));
    }

    /**
     * One firm's answer is replaced rather than added to.
     *
     * <p>An owner sells a ship and takes the name to the next one. The firm's later statement
     * is the one to act on, and a second row would put the resolver back where it started —
     * two rows answering to one name, which is the thing it refuses to choose between.
     */
    @Test
    void replacesThatFirmsEarlierAnswerRatherThanAddingToIt() {
        when(vessels.findById(42L)).thenReturn(java.util.Optional.of(pacificDawn));
        com.chartering.model.IntakeVesselAlias earlier = new com.chartering.model.IntakeVesselAlias();
        earlier.setId(31L);
        earlier.setVesselId(4499L);
        earlier.setName("ALPHA");
        earlier.setNameKey("alpha");
        when(aliases.find(3L, "alpha")).thenReturn(java.util.Optional.of(earlier));

        IntakeItem item = newVesselItem(601L, "ALPHA");
        when(items.findWithEmailById(601L)).thenReturn(java.util.Optional.of(item));
        IntakeItemSource arrival = new IntakeItemSource();
        arrival.setIntakeItem(item);
        arrival.setParsedEmail(parsed);
        arrival.setReportedByCompany(interscan);
        when(itemSources.forItem(601L)).thenReturn(List.of(arrival));

        service.resolve(601L, IntakeService.Action.ALTERNATIVE, null, null, 42L, null, "me");

        verify(aliases).save(argThat(a -> a.getId() != null && a.getId().equals(31L)
                && a.getVesselId().equals(42L)));
    }

    // ------------------------------------------- one question per hull, whichever door

    /**
     * A hull the lookup has just identified joins the question already waiting about her.
     *
     * <p>{@code raiseVesselFields} has always merged into a pending item rather than adding a
     * second; this path never did, and it is the path that reaches the queue most often. The
     * loop it produced is tidy and endless: an email names a hull under a name nothing here
     * carries, a NEW_VESSEL item is raised, the lookup identifies her and the item is
     * converted — and because it is no longer a pending NEW_VESSEL, the next copy of the same
     * list is not recognised as a repeat and raises a fresh one, which converts in its turn.
     * FELIX stood in the queue three times over, MAGDALENA three times, C.EPSILON twice.
     */
    @Test
    void foldsAJustIdentifiedHullIntoTheQuestionAlreadyWaiting() {
        IntakeItem waiting = new IntakeItem();
        waiting.setId(500L);
        waiting.setKind(IntakeItemKind.VESSEL_FIELDS);
        waiting.setStatus(IntakeItemStatus.PENDING);
        waiting.setVesselId(42L);
        waiting.setParsedEmail(parsed);
        waiting.setPayload("""
                {"vesselId":42,"vesselName":"PACIFIC DAWN","matchedBy":"LOOKUP_IMO",
                 "vessel":{"name":"ANGELO"},
                 "diffs":[{"field":"maximumDraft","label":"Draft","current":"6.37 m",
                           "incoming":"6.83 m"}],
                 "filled":[]}""");
        when(items.pendingForVessel(IntakeItemKind.VESSEL_FIELDS, 42L))
                .thenReturn(List.of(waiting));

        IntakeItem arriving = newVesselItem(501L, "ANGELO");
        when(items.pendingByKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(arriving));
        identifiedAs(arriving, pacificDawn);

        assertThat(service.reconcileIdentifiedHulls()).isEqualTo(1);

        // The second question is gone rather than converted, and the first now carries the
        // newer reading as well as its own.
        verify(items).delete(arriving);
        assertThat(arriving.getKind()).isEqualTo(IntakeItemKind.NEW_VESSEL);
        assertThat(waiting.getPayload()).contains("maximumDraft");
        assertThat(waiting.getPayload()).contains("deadweightTonnage");
    }

    /** With nothing waiting, the item still becomes the particulars question it always did. */
    @Test
    void stillConvertsWhenNoQuestionIsWaiting() {
        IntakeItem arriving = newVesselItem(502L, "ANGELO");
        when(items.pendingByKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(arriving));
        identifiedAs(arriving, pacificDawn);

        assertThat(service.reconcileIdentifiedHulls()).isEqualTo(1);

        assertThat(arriving.getKind()).isEqualTo(IntakeItemKind.VESSEL_FIELDS);
        assertThat(arriving.getVesselId()).isEqualTo(42L);
        verify(items, never()).delete(any(IntakeItem.class));
    }

    /** The arrivals move onto the surviving item, so every email stays readable from it. */
    @Test
    void movesTheArrivalsOntoTheSurvivingQuestion() {
        IntakeItem waiting = new IntakeItem();
        waiting.setId(503L);
        waiting.setKind(IntakeItemKind.VESSEL_FIELDS);
        waiting.setStatus(IntakeItemStatus.PENDING);
        waiting.setVesselId(42L);
        waiting.setParsedEmail(parsed);
        waiting.setPayload("""
                {"vesselId":42,"vesselName":"PACIFIC DAWN","matchedBy":"LOOKUP_IMO",
                 "vessel":{"name":"ANGELO"},"diffs":[],"filled":[]}""");
        when(items.pendingForVessel(IntakeItemKind.VESSEL_FIELDS, 42L))
                .thenReturn(List.of(waiting));

        IntakeItem arriving = newVesselItem(504L, "ANGELO");
        when(items.pendingByKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(arriving));
        identifiedAs(arriving, pacificDawn);

        IntakeItemSource itsArrival = new IntakeItemSource();
        itsArrival.setId(70L);
        itsArrival.setIntakeItem(arriving);
        itsArrival.setParsedEmail(parsed);
        when(itemSources.forItem(504L)).thenReturn(List.of(itsArrival));
        when(itemSources.existsByIntakeItemIdAndParsedEmailId(503L, 9L)).thenReturn(false);

        service.reconcileIdentifiedHulls();

        assertThat(itsArrival.getIntakeItem()).isSameAs(waiting);
        verify(itemSources).save(itsArrival);
    }

    /** A NEW_VESSEL item as the sweep leaves one, naming a hull under a name nothing carries. */
    private IntakeItem newVesselItem(Long id, String name) {
        IntakeItem item = new IntakeItem();
        item.setId(id);
        item.setKind(IntakeItemKind.NEW_VESSEL);
        item.setStatus(IntakeItemStatus.PENDING);
        item.setParsedEmail(parsed);
        item.setSubjectLabel(name);
        item.setPayload("""
                {"vessel":{"name":"%s","dwt":32000,"openArea":"MARMARA","openText":"SPOT"},
                 "searchedBy":"the name %s","suggestions":[]}""".formatted(name, name));
        return item;
    }

    /** The lookup came back with a number this database already holds on that hull. */
    private void identifiedAs(IntakeItem item, Vessel vessel) {
        com.chartering.model.VesselLookup row = new com.chartering.model.VesselLookup();
        row.setStatus(com.chartering.model.VesselLookup.STATUS_OK);
        when(lookupService.forItem(item.getId())).thenReturn(java.util.Optional.of(row));
        when(lookupService.alreadyOnFile(row)).thenReturn(java.util.Optional.of(vessel));
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
