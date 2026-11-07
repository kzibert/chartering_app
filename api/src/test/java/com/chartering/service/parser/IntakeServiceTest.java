package com.chartering.service.parser;

import com.chartering.model.*;
import com.chartering.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.chartering.tenancy.TenantContext;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private ParsedEmailRepository parsedEmails;
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
    private VesselFieldReportRepository fieldReports;
    private CompanyDirectoryVersion directory;

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
        parsedEmails = mock(ParsedEmailRepository.class);
        lookupService = mock(com.chartering.service.lookup.VesselLookupService.class);
        decisions = mock(com.chartering.repository.IntakeFieldDecisionRepository.class);
        aliases = mock(com.chartering.repository.IntakeVesselAliasRepository.class);
        fieldReports = mock(VesselFieldReportRepository.class);
        when(decisions.forVessel(any())).thenReturn(List.of());
        when(aliases.find(any(), any())).thenReturn(java.util.Optional.empty());
        styles = mock(CompanyStyleIntake.class);
        when(styles.aggregate(any(), any())).thenAnswer(i -> i.getArgument(1));
        settingsService = mock(com.chartering.service.SettingsService.class);
        directory = mock(CompanyDirectoryVersion.class);
        when(directory.current()).thenReturn(new CompanyDirectoryVersion.Version(0, 0, 0, 0, 0));
        service = new IntakeService(items, cargoSources, cargoes, vessels, exNames, itemSources,
                parsedEmails, decisions, aliases, fieldReports, positions, resolver,
                styles,
                new com.chartering.config.ParserProperties(),
                mock(com.chartering.repository.CompanyRepository.class),
                mock(IntakePasteService.class),
                mock(com.chartering.repository.PersonRepository.class),
                settingsService,
                lookupService,
                mock(com.chartering.service.VesselService.class), new ObjectMapper(),
                directory);
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

    /**
     * A waiting particulars question is weighed on each re-weigh pass against the firm that sent
     * it, and that firm comes from the lean projection rather than from the parse's message.
     *
     * <p>The timer asks this of every waiting item every few minutes, so loading the message to
     * read one company would pull its body across each time. The parse is a mock that must not
     * be asked for its message at all.
     */
    @Test
    void weighsAWaitingQuestionAgainstItsSenderWithoutLoadingTheMessage() throws Exception {
        pacificDawn.setDeadweightTonnage(new BigDecimal("28500"));
        when(vessels.findById(42L)).thenReturn(java.util.Optional.of(pacificDawn));

        ParsedEmail lean = mock(ParsedEmail.class);
        when(lean.getId()).thenReturn(9L);

        IntakeItem waiting = new IntakeItem();
        waiting.setId(11L);
        waiting.setKind(IntakeItemKind.VESSEL_FIELDS);
        waiting.setVesselId(42L);
        waiting.setParsedEmail(lean);
        waiting.setPayload(new ObjectMapper().writeValueAsString(new IntakePayloads.VesselFields(
                42L, "PACIFIC DAWN", null, disagreeing(), List.of(), List.of())));
        when(items.pendingOfKind(IntakeItemKind.VESSEL_FIELDS)).thenReturn(List.of(waiting));
        when(parsedEmails.senderOf(9L)).thenReturn(java.util.Optional.of(interscan));

        service.reweighPending();

        verify(parsedEmails).senderOf(9L);
        verify(lean, never()).getMailMessage();
    }

    // ---------------------------------------------------------- a cargo sent again

    @Test
    void aBrokerResendingHisOwnCargoIsMergedWithoutAsking() {
        // Cargo 122 was asked about seven times, six by a broker already on it as a source.
        Cargo his = new Cargo();
        his.setId(7L);
        his.setCommodity("Wheat");
        his.setQuantity(new BigDecimal("25000"));
        his.setLoadPortText("Chornomorsk");
        his.setLaycanFrom(LocalDate.of(2026, 9, 10));
        his.setLaycanTo(LocalDate.of(2026, 9, 15));
        when(cargoes.findDuplicateCandidates(any())).thenReturn(List.of(his));
        when(cargoSources.cargoIdsReportedBy(3L)).thenReturn(java.util.Set.of(7L));
        when(styles.read(any(), any())).thenReturn(new CompanyStyleIntake.Reading(null, List.of(), null));

        Extraction.ExtractedCargo again = new Extraction.ExtractedCargo(
                "Wheat", new BigDecimal("25000"), "MT", "", null, null, null,
                "Chornomorsk", "", "", "", "2026-09-10", "2026-09-15", "",
                "", "", null, null, null, null, null, null, null,
                "", "", "", "", "");
        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                new Extraction("cargo_offer", List.of(again), List.of(), null, null, null));

        assertThat(outcome.itemsRaised()).isZero();
        assertThat(outcome.cargoesApplied()).isZero();
        verify(items, never()).save(any(IntakeItem.class));
        verify(cargoSources).save(argThat(s -> s.getCargo() == his
                && s.getNotes() != null && s.getNotes().startsWith("Merged on arrival")));
    }

    /**
     * The email's cargo was made on the first reading and has since been fixed. Read again (a
     * re-parse), it must find the cargo it made and not make a second one with this email as its
     * only source.
     */
    @Test
    void aRereadOfAnEmailWhoseCargoHasSinceBeenFixedDoesNotMakeASecondCargo() {
        Cargo fixed = chornomorskWheat();
        fixed.setStatus(CargoStatus.FIXED);
        when(cargoSources.cargoesFromMailMessage(100L)).thenReturn(List.of(fixed));
        when(cargoes.findDuplicateCandidates(any())).thenReturn(List.of());
        when(cargoes.save(any(Cargo.class))).thenAnswer(i -> i.getArgument(0));
        when(styles.read(any(), any())).thenReturn(new CompanyStyleIntake.Reading(null, List.of(), null));

        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                new Extraction("cargo_offer", List.of(wheatCargo()), List.of(), null, null, null));

        assertThat(outcome.cargoesApplied()).isZero();
        verify(cargoes, never()).save(any(Cargo.class));
        verify(cargoSources, never()).save(any(CargoSource.class));
    }

    /**
     * A re-read is not a reason to stop reading. The first pass made the wheat; the email also
     * offers barley, which the first pass missed, and the barley is still created.
     */
    @Test
    void aRereadEmailWithASecondCargoItFirstMissedStillMakesThatOne() {
        Cargo fixed = chornomorskWheat();
        fixed.setStatus(CargoStatus.FIXED);
        when(cargoSources.cargoesFromMailMessage(100L)).thenReturn(List.of(fixed));
        when(cargoes.findDuplicateCandidates(any())).thenReturn(List.of());
        when(cargoes.save(any(Cargo.class))).thenAnswer(i -> i.getArgument(0));
        when(styles.read(any(), any())).thenReturn(new CompanyStyleIntake.Reading(null, List.of(), null));

        Extraction.ExtractedCargo barley = new Extraction.ExtractedCargo(
                "Barley", new BigDecimal("20000"), "MT", "", null, null, null,
                "Odessa", "", "", "", "2026-09-12", "2026-09-18", "",
                "", "", null, null, null, null, null, null, null,
                "", "", "", "", "");
        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                new Extraction("cargo_offer", List.of(wheatCargo(), barley), List.of(), null, null, null));

        assertThat(outcome.cargoesApplied()).isEqualTo(1);
        ArgumentCaptor<Cargo> made = ArgumentCaptor.forClass(Cargo.class);
        verify(cargoes).save(made.capture());
        assertThat(made.getValue().getCommodity()).isEqualTo("Barley");
    }

    /**
     * Same figure and load port, no laycan on file, and nobody from this firm has sent it before:
     * a question, not a certainty. It is raised minor, so it waits on the cargo's drawer rather
     * than holding up the Needs review count.
     */
    @Test
    void aProbableDuplicateIsRaisedMinorSoItDoesNotHoldUpTheCount() {
        Cargo theirs = chornomorskWheat();
        theirs.setLaycanFrom(null);
        theirs.setLaycanTo(null);
        when(cargoes.findDuplicateCandidates(any())).thenReturn(List.of(theirs));
        when(styles.read(any(), any())).thenReturn(new CompanyStyleIntake.Reading(null, List.of(), null));

        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                new Extraction("cargo_offer", List.of(wheatCargo()), List.of(), null, null, null));

        assertThat(outcome.itemsRaised()).isEqualTo(1);
        verify(items, atLeastOnce()).save(argThat(i -> i.getKind() == IntakeItemKind.CARGO_MERGE
                && i.isMinor() && i.getCargoId() != null && i.getCargoId() == 7L));
    }

    /**
     * The reweigh pass is the one place a waiting merge is decided without a person. Here the
     * sender has since become one the cargo already holds, so the figure alone makes it certain
     * and it is merged; the other waiting question is about a cargo from someone else, stays
     * waiting, and is minor.
     */
    @Test
    void reweighMergesAWaitingDuplicateThatHasBecomeCertainAndMarksTheRestMinor() throws Exception {
        Cargo his = chornomorskWheat();
        his.setLaycanFrom(null);
        his.setLaycanTo(null);
        Cargo theirs = chornomorskWheat();
        theirs.setId(8L);
        theirs.setLaycanFrom(null);
        theirs.setLaycanTo(null);
        when(cargoes.findById(7L)).thenReturn(java.util.Optional.of(his));
        when(cargoes.findById(8L)).thenReturn(java.util.Optional.of(theirs));
        // The sender is read off the parse, not off its message, and asked of this cargo alone.
        when(parsedEmails.senderOf(parsed.getId())).thenReturn(java.util.Optional.of(interscan));
        when(cargoSources.existsByCargoIdAndReportedByCompanyIdIn(7L, List.of(3L))).thenReturn(true);

        IntakeItem sameBroker = waitingMerge(21L, 7L);
        IntakeItem otherFirm = waitingMerge(22L, 8L);
        when(items.pendingOfKind(IntakeItemKind.CARGO_MERGE)).thenReturn(List.of(sameBroker, otherFirm));

        service.reweighPending();

        assertThat(sameBroker.getStatus()).isEqualTo(IntakeItemStatus.ACCEPTED);
        assertThat(sameBroker.getResolutionNote()).startsWith("Merged automatically: ");
        verify(cargoSources).save(argThat(s -> s.getCargo() == his
                && s.getNotes() != null && s.getNotes().startsWith("Merged automatically")));
        assertThat(otherFirm.getStatus()).isEqualTo(IntakeItemStatus.PENDING);
        assertThat(otherFirm.isMinor()).isTrue();
        verify(cargoSources, never()).save(argThat(s -> s.getCargo() == theirs));
    }

    private IntakeItem waitingMerge(Long id, Long cargoId) throws Exception {
        IntakeItem item = new IntakeItem();
        item.setId(id);
        item.setKind(IntakeItemKind.CARGO_MERGE);
        item.setCargoId(cargoId);
        item.setParsedEmail(parsed);
        item.setPayload(new ObjectMapper().writeValueAsString(new IntakePayloads.CargoMerge(
                wheatCargo(), cargoId, "Wheat", List.of(), List.of(), List.of())));
        return item;
    }

    private static Extraction.ExtractedCargo wheatCargo() {
        return new Extraction.ExtractedCargo(
                "Wheat", new BigDecimal("25000"), "MT", "", null, null, null,
                "Chornomorsk", "", "", "", "2026-09-10", "2026-09-15", "",
                "", "", null, null, null, null, null, null, null,
                "", "", "", "", "");
    }

    private static Cargo chornomorskWheat() {
        Cargo c = new Cargo();
        c.setId(7L);
        c.setCommodity("Wheat");
        c.setQuantity(new BigDecimal("25000"));
        c.setLoadPortText("Chornomorsk");
        c.setLaycanFrom(LocalDate.of(2026, 9, 10));
        c.setLaycanTo(LocalDate.of(2026, 9, 15));
        return c;
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
        return new Extraction("other", List.of(), List.of(), null, null, null);
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
        return new Extraction("vessel_opening", List.of(), List.of(vs), null, null, null);
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

    /**
     * The sweep reads newest first, so after a backlog an older email from a broker is read after
     * a newer one from the same broker. Its reading is not a later statement and must not take the
     * newer one's place on Open Fleet; it is kept on her record as history.
     */
    @Test
    void anOlderReadingReadAfterANewerOneFromTheSameBrokerIsKeptAsHistoryOnly() {
        when(resolver.resolveVessel(any(), any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));
        VesselPosition newer = live(interscan, LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 7),
                "5/7 SEPT", OffsetDateTime.parse("2026-09-06T08:30:00+02:00"));
        onFile.add(newer);

        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("PACIFIC DAWN", "1/3 SEPT", "2026-09-01", "2026-09-03")));

        // The newer list is still the one Open Fleet shows.
        assertThat(newer.getStatus()).isEqualTo(PositionStatus.LIVE);
        // The older reading is recorded, but as history.
        ArgumentCaptor<VesselPosition> saved = ArgumentCaptor.forClass(VesselPosition.class);
        verify(positions).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PositionStatus.SUPERSEDED);
        assertThat(saved.getValue().getOpenFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        // Nothing on Open Fleet changed, so it is not counted as an applied position.
        assertThat(outcome.positionsApplied()).isZero();
    }

    /**
     * The same email read again, or a second copy of the same list, carries the sender's own clock
     * unchanged. Nothing is later than it, so the earlier reading is replaced as before.
     */
    @Test
    void aReadingAtTheSameInstantAsTheLiveOneReplacesIt() {
        when(resolver.resolveVessel(any(), any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));
        OffsetDateTime sameInstant = message.getSentAt().atZone(ZoneId.systemDefault()).toOffsetDateTime();
        VesselPosition earlier = live(interscan, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3),
                "1/3 SEPT", sameInstant);
        onFile.add(earlier);

        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("PACIFIC DAWN", "5/7 SEPT", "2026-09-05", "2026-09-07")));

        assertThat(earlier.getStatus()).isEqualTo(PositionStatus.SUPERSEDED);
        ArgumentCaptor<VesselPosition> saved = ArgumentCaptor.forClass(VesselPosition.class);
        verify(positions).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PositionStatus.LIVE);
        assertThat(outcome.positionsApplied()).isEqualTo(1);
    }

    @Test
    void recordsANewRowAndSupersedesTheSameBrokersPreviousOneWhenTheDatesMove() {
        when(resolver.resolveVessel(any(), any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(pacificDawn, IntakeResolver.VesselMatch.NAME));
        // Told on the 3rd, before the email under test (sent on the 4th): it is the earlier list.
        VesselPosition yesterday = live(interscan, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3),
                "1/3 SEPT", OffsetDateTime.parse("2026-09-03T08:30:00+02:00"));
        onFile.add(yesterday);

        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("PACIFIC DAWN", "5/7 SEPT", "2026-09-05", "2026-09-07")));

        verify(positions).save(any(VesselPosition.class));
        assertThat(yesterday.getStatus()).isEqualTo(PositionStatus.SUPERSEDED);
        assertThat(outcome.positionsApplied()).isEqualTo(1);
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
        when(items.pendingOfKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(newVessel));

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
        when(items.pendingOfKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(newVessel));

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
        when(items.pendingOfKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(newVessel));
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

    @Test
    void ignoresAVesselTheModelReturnedWithNoName() {
        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("", "SPOT", null, null)));

        assertThat(outcome.positionsApplied()).isZero();
        assertThat(outcome.itemsRaised()).isZero();
        verify(items, never()).save(any());
    }

    // ---------------------------------------------------------- a sender named late

    @Test
    void aPositionFiledFromNobodyGainsItsReporterOnceTheSenderIsOnFile() {
        // Filed while the address was unknown; the mailbox has since linked it to Interscan.
        VesselPosition filed = position(501L, null, OffsetDateTime.parse("2026-09-18T08:00:00Z"));
        filed.setSourceMailMessage(message);
        // Interscan's own earlier reading of her: now two LIVE rows from one reporter, and the
        // older is the one a list replaced.
        VesselPosition earlier = position(400L, interscan, OffsetDateTime.parse("2026-09-10T08:00:00Z"));
        onFile.add(filed);
        onFile.add(earlier);
        when(positions.findUnreportedFromMail()).thenReturn(List.of(filed));

        int named = service.attributeUnreported();

        assertThat(named).isEqualTo(1);
        assertThat(filed.getReportedByCompany()).isSameAs(interscan);
        assertThat(filed.getStatus()).isEqualTo(PositionStatus.LIVE);
        assertThat(earlier.getStatus()).isEqualTo(PositionStatus.SUPERSEDED);
    }

    @Test
    void aSignatureThatStillMatchesNobodyLeavesTheRowAlone() {
        FeedItem post = new FeedItem();
        post.setId(300L);
        post.setText("MV AGN LAGERTHA /27-29 SEPT MARMARA\n\nSomebody\nA Firm Not On File");
        when(styles.read(any(), any())).thenReturn(new CompanyStyleIntake.Reading(null, List.of(), null));
        Cargo cargo = new Cargo();
        cargo.setId(8L);
        cargo.setSourceFeedItem(post);
        when(cargoes.findUnbrokeredFromPost()).thenReturn(List.of(cargo));

        assertThat(service.attributeUnreported()).isZero();
        assertThat(cargo.getBrokerCompany()).isNull();
    }

    @Test
    void aBoardPostIsNotReadAgainWhileTheDeskDirectoryIsUnchanged() {
        // A post whose firm is not on file: its signature is read on every pass that reads posts.
        FeedItem post = new FeedItem();
        post.setId(301L);
        post.setText("MV AGN LAGERTHA /27-29 SEPT MARMARA\n\nSomebody\nA Firm Not On File");
        when(styles.read(any(), any())).thenReturn(new CompanyStyleIntake.Reading(null, List.of(), null));
        Cargo cargo = new Cargo();
        cargo.setId(9L);
        cargo.setSourceFeedItem(post);
        when(cargoes.findUnbrokeredFromPost()).thenReturn(List.of(cargo));
        when(directory.current()).thenReturn(new CompanyDirectoryVersion.Version(4, 40, 3, 5, 12));

        TenantContext.runAs(1L, () -> {
            service.attributeUnreported();
            service.attributeUnreported();
        });
        // Read once, on the first pass; the second found the directory where the first left it.
        verify(styles, times(1)).read(any(), any());

        // A company, a person or an address was added: the directory moved, so the post is asked
        // again - and this time the firm is there to name.
        when(directory.current()).thenReturn(new CompanyDirectoryVersion.Version(5, 41, 4, 5, 12));
        TenantContext.runAs(1L, () -> service.attributeUnreported());
        verify(styles, times(2)).read(any(), any());
    }

    // ---------------------------------------------------------- placeholder slots

    /**
     * A TBN with no number is a berth on a fixture, not a ship. Nothing is raised or filed for it,
     * and the resolver is never asked, so there is no hull for it to be matched to.
     */
    @Test
    void skipsAnUnnominatedSlotWithNoNumber() {
        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("TBN", "SPOT", null, null)));

        assertThat(outcome.positionsApplied()).isZero();
        assertThat(outcome.itemsRaised()).isZero();
        verify(resolver, never()).resolveVessel(any(), any());
        verify(items, never()).save(any());
        verify(positions, never()).save(any());
    }

    /**
     * A legacy hull that happens to be called TBN, carrying its IMO. It is a real ship and goes
     * through as any other: the IMO is what makes it one.
     */
    @Test
    void aSlotNamedLikeAShipWithANumberStillGoesThrough() {
        Vessel legacy = new Vessel();
        legacy.setId(77L);
        legacy.setName("TBN");
        when(resolver.resolveVessel(any(), any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(legacy, IntakeResolver.VesselMatch.IMO));

        Extraction.ExtractedVessel numbered = new Extraction.ExtractedVessel(
                "TBN", "9123456", "", null, null, null, null, "",
                null, null, "", null, "", null, null, null, null, null, "",
                "MARMARA", "", "", "", "SPOT", "", "", "");
        IntakeService.ApplyOutcome outcome = service.apply(parsed, positionEmail(numbered));

        assertThat(outcome.positionsApplied()).isEqualTo(1);
    }

    @Test
    void closesAWaitingPlaceholderSlotAsRejectedWithItsReason() {
        IntakeItem slot = newVesselItem(20L, "{\"vessel\":{\"name\":\"TBN 2\"},"
                + "\"searchedBy\":\"TBN 2\",\"suggestions\":[]}");
        when(items.pendingOfKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(slot));

        service.reweighPending();

        assertThat(slot.getStatus()).isEqualTo(IntakeItemStatus.REJECTED);
        assertThat(slot.getResolutionNote()).isEqualTo(IntakeService.PLACEHOLDER_CLOSED);
        assertThat(slot.getResolvedBy()).isNull();
        verify(items).save(slot);
    }

    /** The same name with a number on it is a hull someone may still name; it stays waiting. */
    @Test
    void leavesAWaitingNumberedSlotPending() {
        IntakeItem numbered = newVesselItem(21L, "{\"vessel\":{\"name\":\"TBN\",\"imo\":\"9123456\"},"
                + "\"searchedBy\":\"TBN\",\"suggestions\":[]}");
        when(items.pendingOfKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(numbered));

        service.reweighPending();

        assertThat(numbered.getStatus()).isEqualTo(IntakeItemStatus.PENDING);
        verify(items, never()).save(numbered);
    }

    // ---------------------------------------------------------- discard memory

    /**
     * The reviewer has already said this name is not a ship to create. A broker sending it again
     * raises nothing, and nothing is written for the email beyond what it always was.
     */
    @Test
    void doesNotRaiseANewVesselTheDeskHasAlreadyTurnedDown() {
        when(resolver.resolveVessel(any(), any()))
                .thenReturn(new IntakeResolver.ResolvedVessel(null, IntakeResolver.VesselMatch.NONE));
        IntakeItem turnedDown = newVesselItem(22L, "{}");
        turnedDown.setStatus(IntakeItemStatus.REJECTED);
        when(items.rejectedNewVesselSince(any(), any())).thenReturn(List.of(turnedDown));

        IntakeService.ApplyOutcome outcome = service.apply(parsed,
                positionEmail(opening("UNKNOWN TRADER", "SPOT", null, null)));

        assertThat(outcome.itemsRaised()).isZero();
        verify(items, never()).save(any());
    }

    // ---------------------------------------------------------- one question per vessel

    /**
     * The conversion path: a lookup turned a waiting NEW_VESSEL into a particulars question about
     * a hull that already has one waiting. The two are merged; the position is still filed.
     */
    @Test
    void aConvertedNewVesselMergesIntoTheParticularsQuestionAlreadyWaiting() {
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
        when(items.pendingOfKind(IntakeItemKind.NEW_VESSEL)).thenReturn(List.of(newVessel));

        IntakeItem waiting = new IntakeItem();
        waiting.setId(3L);
        waiting.setKind(IntakeItemKind.VESSEL_FIELDS);
        waiting.setVesselId(2776L);
        waiting.setPayload("""
                {"vesselId":2776,"vesselName":"CELIA","diffs":[
                  {"field":"deadweightTonnage","label":"DWT","current":"2000 t","incoming":"2100 t"}],
                 "filled":[]}""");
        when(items.pendingForVessel(IntakeItemKind.VESSEL_FIELDS, 2776L)).thenReturn(List.of(waiting));

        VesselLookup row = new VesselLookup();
        row.setStatus(VesselLookup.STATUS_OK);
        row.setMatchedImo("9344394");
        when(lookupService.forItem(6L)).thenReturn(java.util.Optional.of(row));
        when(lookupService.alreadyOnFile(row)).thenReturn(java.util.Optional.of(celia));

        assertThat(service.reconcileIdentifiedHulls()).isEqualTo(1);

        assertThat(newVessel.getStatus()).isEqualTo(IntakeItemStatus.ACCEPTED);
        assertThat(newVessel.getResolutionNote()).contains("#3");
        assertThat(newVessel.getKind()).isEqualTo(IntakeItemKind.NEW_VESSEL);
        // The waiting question takes the new reading's rows alongside its own.
        assertThat(waiting.getStatus()).isEqualTo(IntakeItemStatus.PENDING);
        assertThat(waiting.getPayload()).contains("flag").contains("deadweightTonnage");
        verify(itemSources).save(any());
        verify(positions).save(any());
    }

    /**
     * Two particulars questions for one hull, as the queue held them before the rule. The reweigh
     * pass folds the newer into the oldest, keeps both emails on the oldest, and closes the newer.
     */
    @Test
    void reweighFoldsASecondParticularsQuestionAboutOneHullIntoTheOldest() {
        when(vessels.findById(42L)).thenReturn(java.util.Optional.of(pacificDawn));

        IntakeItem oldest = particularsItem(1L, "{\"vesselId\":42,\"vesselName\":\"PACIFIC DAWN\","
                + "\"diffs\":[{\"field\":\"deadweightTonnage\",\"label\":\"DWT\","
                + "\"current\":\"28500 t\",\"incoming\":\"32000 t\"}],\"filled\":[],"
                + "\"vessel\":{\"name\":\"PACIFIC DAWN\",\"dwt\":32000}}");
        IntakeItem newer = particularsItem(2L, "{\"vesselId\":42,\"vesselName\":\"PACIFIC DAWN\","
                + "\"diffs\":[{\"field\":\"maximumDraft\",\"label\":\"Draft\","
                + "\"current\":\"7.9 m\",\"incoming\":\"8.2 m\"}],\"filled\":[],"
                + "\"vessel\":{\"name\":\"PACIFIC DAWN\",\"draft\":8.2}}");
        newer.setParsedEmail(parsed);
        when(items.pendingOfKind(IntakeItemKind.VESSEL_FIELDS)).thenReturn(List.of(oldest, newer));

        service.reweighPending();

        assertThat(newer.getStatus()).isEqualTo(IntakeItemStatus.ACCEPTED);
        assertThat(newer.getResolutionNote()).contains("#1");
        assertThat(oldest.getStatus()).isEqualTo(IntakeItemStatus.PENDING);
        assertThat(oldest.getPayload()).contains("deadweightTonnage").contains("maximumDraft");
        // The newer email is kept on the question that is still open.
        ArgumentCaptor<IntakeItemSource> kept = ArgumentCaptor.forClass(IntakeItemSource.class);
        verify(itemSources).save(kept.capture());
        assertThat(kept.getValue().getIntakeItem()).isSameAs(oldest);
    }

    /**
     * Every row already agrees with the record, so there is nothing for a person to decide. Left
     * pending, such an item would be empty on the screen and read as "not minor" by the policy,
     * which keeps it in Needs review indefinitely.
     */
    @Test
    void closesAParticularsQuestionWhoseRowsAllAgreeWithTheRecord() {
        pacificDawn.setDeadweightTonnage(new BigDecimal("28500"));
        when(vessels.findById(42L)).thenReturn(java.util.Optional.of(pacificDawn));

        IntakeItem settled = particularsItem(5L, "{\"vesselId\":42,\"vesselName\":\"PACIFIC DAWN\","
                + "\"diffs\":[{\"field\":\"deadweightTonnage\",\"label\":\"DWT\","
                + "\"current\":\"28500 t\",\"incoming\":\"28500 t\"}],\"filled\":[],"
                + "\"vessel\":{\"name\":\"PACIFIC DAWN\",\"dwt\":28500}}");
        when(items.pendingOfKind(IntakeItemKind.VESSEL_FIELDS)).thenReturn(List.of(settled));

        service.reweighPending();

        assertThat(settled.getStatus()).isEqualTo(IntakeItemStatus.ACCEPTED);
        assertThat(settled.getResolutionNote()).isEqualTo(IntakeService.NOTHING_LEFT);
        verify(items).save(settled);
    }

    private IntakeItem newVesselItem(Long id, String payload) {
        IntakeItem item = new IntakeItem();
        item.setId(id);
        item.setKind(IntakeItemKind.NEW_VESSEL);
        item.setParsedEmail(parsed);
        item.setSubjectLabel("TBN");
        item.setStatus(IntakeItemStatus.PENDING);
        item.setPayload(payload);
        return item;
    }

    private IntakeItem particularsItem(Long id, String payload) {
        IntakeItem item = new IntakeItem();
        item.setId(id);
        item.setKind(IntakeItemKind.VESSEL_FIELDS);
        item.setVesselId(42L);
        item.setSubjectLabel("PACIFIC DAWN");
        item.setStatus(IntakeItemStatus.PENDING);
        item.setPayload(payload);
        return item;
    }

    private VesselPosition position(Long id, Company reporter, OffsetDateTime reportedAt) {
        VesselPosition p = new VesselPosition();
        p.setId(id);
        p.setVessel(pacificDawn);
        p.setStatus(PositionStatus.LIVE);
        p.setReportedByCompany(reporter);
        p.setReportedAt(reportedAt);
        return p;
    }
}
