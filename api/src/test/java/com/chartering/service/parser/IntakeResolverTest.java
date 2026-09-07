package com.chartering.service.parser;

import com.chartering.model.Vessel;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.PortRepository;
import com.chartering.repository.TradeAreaRepository;
import com.chartering.repository.VesselRepository;
import com.chartering.service.TradeAreaGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Matching a reading to a hull, in the order the desk would: the IMO, then the name, then a
 * shortlist for a person to choose from.
 *
 * <p>The first two decide on their own, so their tests are mostly about what they refuse.
 * The third decides nothing, so its test is about honesty: a suggestion is offered with its
 * figures, and a figure that does not support the suggestion must not be printed as though
 * it did.
 */
class IntakeResolverTest {

    private VesselRepository vessels;
    private IntakeResolver resolver;

    @BeforeEach
    void setUp() {
        vessels = mock(VesselRepository.class);
        resolver = new IntakeResolver(vessels, mock(PortRepository.class),
                mock(CompanyRepository.class), mock(TradeAreaRepository.class),
                mock(TradeAreaGraph.class));
    }

    private static Vessel vessel(long id, String name, String dwt, Integer built) {
        Vessel v = new Vessel();
        v.setId(id);
        v.setName(name);
        if (dwt != null) v.setDeadweightTonnage(new BigDecimal(dwt));
        v.setYearBuilt(built);
        return v;
    }

    private static Extraction.ExtractedVessel reading(String name, String imo, String dwt,
                                                      Integer built) {
        return new Extraction.ExtractedVessel(
                name, imo == null ? "" : imo, "",
                dwt == null ? null : new BigDecimal(dwt), null, null, built, "",
                null, null, "", null, "", null, null, null, null, null, "",
                "", "", "", "", "", "", "", "");
    }

    // ------------------------------------------------------------------ tier 1 and 2

    @Test
    void takesTheImoOverTheNameWhenBothAreGiven() {
        Vessel renamed = vessel(1L, "LOIRE RIVER", "5000", 2005);
        when(vessels.findByImoNumber("9123456")).thenReturn(List.of(renamed));

        IntakeResolver.ResolvedVessel match =
                resolver.resolveVessel(reading("AMIKO", "IMO 9123456", null, null));

        assertThat(match.found()).isTrue();
        assertThat(match.how()).isEqualTo(IntakeResolver.VesselMatch.IMO);
        assertThat(match.vessel()).isSameAs(renamed);
    }

    @Test
    void fallsThroughToTheNameWhenTheImoMatchesNothing() {
        when(vessels.findByImoNumber(any())).thenReturn(List.of());
        Vessel onFile = vessel(1L, "PACIFIC DAWN", "28500", 2003);
        when(vessels.findByExactName("PACIFIC DAWN")).thenReturn(List.of(onFile));

        // Position lists mostly carry no IMO, and the ones that do often carry it for a hull
        // entered here years before anybody recorded them.
        IntakeResolver.ResolvedVessel match =
                resolver.resolveVessel(reading("PACIFIC DAWN", "IMO 9999999", null, null));

        assertThat(match.how()).isEqualTo(IntakeResolver.VesselMatch.NAME);
    }

    @Test
    void reportsAMatchOnAFormerNameAsSuch() {
        when(vessels.findByImoNumber(any())).thenReturn(List.of());
        Vessel onFile = vessel(1L, "LOIRE RIVER", "5000", 2005);
        when(vessels.findByExactName("AMIKO")).thenReturn(List.of(onFile));

        IntakeResolver.ResolvedVessel match = resolver.resolveVessel(reading("AMIKO", null, null, null));

        // Right answer that looks wrong — the row comes back called something else — so the
        // reviewer is told which of the two names matched.
        assertThat(match.how()).isEqualTo(IntakeResolver.VesselMatch.EX_NAME);
    }

    @Test
    void declinesToChooseBetweenTwoRowsSharingAnIdentifier() {
        when(vessels.findByImoNumber("9123456"))
                .thenReturn(List.of(vessel(1L, "ONE", null, null), vessel(2L, "TWO", null, null)));
        when(vessels.findByExactName(any()))
                .thenReturn(List.of(vessel(3L, "A", null, null), vessel(4L, "B", null, null)));

        // A data fault. Picking whichever came first would hide it behind a position that
        // looks perfectly ordinary.
        assertThat(resolver.resolveVessel(reading("A", "9123456", null, null)).found()).isFalse();
    }

    // ------------------------------------------------------------------ tier 3

    @Test
    void doesNotOfferADeadweightAsEvidenceWhenItIsNowhereNear() {
        // "ANKA" shares four letters with "ANKA BLUE" and is less than half her size. The
        // name is worth mentioning; the deadweight is not, and printing it would read as a
        // reason to believe the two are one ship.
        when(vessels.findSimilar(any(), any(), any(), any()))
                .thenReturn(List.of(vessel(1L, "ANKA", "3344", 2001)));

        List<IntakeResolver.Suggestion> suggestions =
                resolver.suggest(reading("ANKA BLUE", null, "8181", null));

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).reason()).doesNotContain("DWT");
        assertThat(suggestions.get(0).reason()).contains("name starts the same");
    }

    @Test
    void ranksTheHullWhoseParticularsActuallyMatchAbove() {
        when(vessels.findSimilar(any(), any(), any(), any())).thenReturn(List.of(
                vessel(1L, "ANKA", "3344", 2001),
                vessel(2L, "BERTA", "8200", 2004)));

        List<IntakeResolver.Suggestion> suggestions =
                resolver.suggest(reading("ANKA BLUE", null, "8181", 2004));

        assertThat(suggestions).extracting(IntakeResolver.Suggestion::name)
                .containsExactly("BERTA", "ANKA");
        assertThat(suggestions.get(0).reason()).contains("DWT 8200 against 8181", "built 2004");
    }

    @Test
    void suggestsNothingWhenTheEmailGaveNothingToGoOn() {
        // No size and a name too short to key on. An empty shortlist is the honest answer;
        // a screen that always offers suggestions teaches the reader they mean nothing.
        assertThat(resolver.suggest(reading("MV", null, null, null))).isEmpty();
    }

    // ------------------------------------------------------------------ small readings

    @Test
    void readsAnImoOnlyWhenItIsSevenDigits() {
        assertThat(IntakeResolver.normaliseImo("IMO 9123456")).isEqualTo("9123456");
        assertThat(IntakeResolver.normaliseImo("imo9123456")).isEqualTo("9123456");
        assertThat(IntakeResolver.normaliseImo("91234")).isNull();
        assertThat(IntakeResolver.normaliseImo("")).isNull();
    }

    @Test
    void keepsAPhraseThatIsNotADateOutOfADateColumn() {
        assertThat(IntakeResolver.date("2026-09-01")).isEqualTo(LocalDate.of(2026, 9, 1));
        // "SPOT" and "end Sept" are correct answers to "when does she open" — just not date
        // ones. They live in the text column the model was told to put them in.
        assertThat(IntakeResolver.date("SPOT")).isNull();
        assertThat(IntakeResolver.date("end Sept")).isNull();
        assertThat(IntakeResolver.date("")).isNull();
    }
    @Test
    void switchesTheNameArmOffWithSomethingTheDatabaseWillAccept() {
        // TBN is what a circular calls a ship it has not nominated yet, and it is on position
        // lists constantly. Three characters, so the name arm is switched off - and the
        // pattern that switched it off used to be a NUL character. Postgres does not read
        // that as "matches no row", it refuses the statement outright with
        // "invalid byte sequence for encoding UTF8: 0x00", and the failing statement took the
        // whole parse of the email down with it. It reads as a blank in every editor, which
        // is how it survived being looked at.
        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        when(vessels.findSimilar(any(), any(), prefix.capture(), any())).thenReturn(List.of());

        resolver.suggest(reading("TBN", null, "28000", 2005));

        assertThat(prefix.getValue()).doesNotContain(String.valueOf((char) 0));
        // And it still matches nothing: no wildcard in it, so LIKE is an equality test
        // against a name no owner has ever given a ship.
        assertThat(prefix.getValue()).doesNotContain("%");
    }

}
