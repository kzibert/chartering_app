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
        service = new IntakeService(items, cargoSources, cargoes, vessels, exNames, positions,
                resolver, new ObjectMapper());

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
    void doesNotAskTheSameQuestionTwiceWhileItIsStillWaiting() {
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
        verify(items, never()).save(any());
        // The position still lands, because that is a different fact.
        assertThat(outcome.positionsApplied()).isEqualTo(1);
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
