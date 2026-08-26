package com.chartering.service;

import com.chartering.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides which ships get offered.
 *
 * <p>The tests worth reading here are the ones about missing data. Half this fleet has no
 * gear recorded and two thousand hulls have no DWCC, so how the scorer treats a blank is not
 * an edge case — it is the common case, and getting it wrong in either direction is how a
 * matching screen becomes something nobody trusts.
 */
class MatchScorerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    // The real vocabulary is a database table; these tests only need it to answer two
    // questions, so it is stubbed rather than loaded.
    private final TradeAreaGraph areas = Mockito.mock(TradeAreaGraph.class);

    private final TradeArea bsea = area(1L, "BSEA");
    private final TradeArea wmed = area(2L, "WMED");
    private final TradeArea caspian = area(3L, "CASP");

    // ------------------------------------------------------------------ size

    @Test
    void offersAShipThatCanLiftTheLowEndOfTheRange() {
        // 25,000 +/- 10% means the charterer will ship 22,500, and the tolerance exists
        // precisely so a slightly smaller hull can work.
        Cargo c = cargo();
        c.setQuantity(new BigDecimal("25000"));
        c.setQuantityMin(new BigDecimal("22500"));
        c.setQuantityMax(new BigDecimal("27500"));

        MatchScorer.Result r = score(c, position(vessel(v -> v.setDeadweightCargoCapacity(new BigDecimal("23000")))));

        assertThat(r.ruledOut()).isFalse();
        assertThat(check(r, "size").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
    }

    @Test
    void rulesOutAShipTooSmallEvenForTheLowEnd() {
        Cargo c = cargo();
        c.setQuantity(new BigDecimal("25000"));
        c.setQuantityMin(new BigDecimal("22500"));

        MatchScorer.Result r = score(c, position(vessel(v -> v.setDeadweightCargoCapacity(new BigDecimal("6100")))));

        assertThat(r.ruledOut()).isTrue();
        assertThat(check(r, "size").detail()).contains("6100").contains("22500");
    }

    @Test
    void fallsBackToDeadweightWhenNoCargoCapacityIsRecorded() {
        // 2,355 vessels have a DWT and no DWCC. Reading the 0 as a real figure would rule
        // every one of them out of every cargo.
        Cargo c = cargo();
        c.setQuantityMin(new BigDecimal("5000"));
        Vessel v = vessel(x -> {
            x.setDeadweightCargoCapacity(BigDecimal.ZERO);
            x.setDeadweightTonnage(new BigDecimal("6354"));
        });

        assertThat(check(score(c, position(v)), "size").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
    }

    @Test
    void saysUnknownRatherThanNoWhenNeitherFigureIsOnFile() {
        Cargo c = cargo();
        c.setQuantityMin(new BigDecimal("5000"));
        Vessel v = vessel(x -> {
            x.setDeadweightCargoCapacity(BigDecimal.ZERO);
            x.setDeadweightTonnage(BigDecimal.ZERO);
        });

        MatchScorer.Result r = score(c, position(v));
        assertThat(check(r, "size").verdict()).isEqualTo(MatchScorer.Verdict.UNKNOWN);
        assertThat(r.ruledOut()).as("missing data is not an answer").isFalse();
    }

    // ------------------------------------------------------------------ gear

    @Test
    void rulesOutAGearlessShipForACargoThatNeedsGear() {
        Cargo c = cargo();
        c.setRequiresGeared(true);

        MatchScorer.Result r = score(c, position(vessel(v -> v.setGeared(false))));

        assertThat(r.ruledOut()).isTrue();
        assertThat(check(r, "gear").detail()).isEqualTo("Gearless, cargo wants gear");
    }

    @Test
    void keepsAShipWhoseGearIsSimplyNotRecorded() {
        Cargo c = cargo();
        c.setRequiresGeared(true);

        MatchScorer.Result r = score(c, position(vessel(v -> v.setGeared(null))));

        assertThat(r.ruledOut()).isFalse();
        assertThat(check(r, "gear").verdict()).isEqualTo(MatchScorer.Verdict.UNKNOWN);
        assertThat(r.unknownCount()).isEqualTo(1);
    }

    @Test
    void doesNotPenaliseCranesTheCargoDoesNotNeed() {
        // Asymmetric on purpose: gear she does not need costs the charterer nothing, so
        // "gear not required" must not rule out a geared ship.
        Cargo c = cargo();
        c.setRequiresGeared(false);

        MatchScorer.Result r = score(c, position(vessel(v -> v.setGeared(true))));

        assertThat(r.checks()).noneMatch(x -> x.code().equals("gear"));
        assertThat(r.ruledOut()).isFalse();
    }

    // -------------------------------------------------------------- position

    @Test
    void countsAShipAlreadyInTheLoadAreaAsThere() {
        Cargo c = cargo();
        c.setLoadArea(bsea);
        Mockito.when(areas.ballastDays(1L, 1L)).thenReturn(OptionalDouble.of(0));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(bsea);
        p.setOpenFrom(LocalDate.of(2026, 9, 1));

        MatchScorer.Result r = MatchScorer.score(c, p, areas, TODAY);
        assertThat(check(r, "timing").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
        assertThat(r.ballastDays()).isZero();
    }

    @Test
    void rulesOutAShipThatCannotMakeTheCancellingDate() {
        Cargo c = cargo();
        c.setLoadArea(bsea);
        c.setLaycanTo(LocalDate.of(2026, 9, 5));
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(6));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(wmed);
        p.setOpenFrom(LocalDate.of(2026, 9, 1));
        p.setOpenTo(LocalDate.of(2026, 9, 2));

        MatchScorer.Result r = MatchScorer.score(c, p, areas, TODAY);

        // Free on the 2nd, six days' ballast, so the 8th - three days past cancelling.
        assertThat(r.ruledOut()).isTrue();
        assertThat(r.earliestArrival()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(check(r, "timing").detail()).contains("arrives 2026-09-08");
    }

    @Test
    void countsFromTheLastFreeDayNotTheFirst() {
        // A ship open 1/3 September is not sailing on the 1st. Using the optimistic end
        // would put ships on lists they cannot make.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(2));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(wmed);
        p.setOpenFrom(LocalDate.of(2026, 9, 1));
        p.setOpenTo(LocalDate.of(2026, 9, 3));

        assertThat(MatchScorer.score(c, p, areas, TODAY).earliestArrival())
                .isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    void rulesOutAPairNothingOnFileConnects() {
        // The Caspian is landlocked and deliberately has no distances at all. "Unknown"
        // here would invite offering a Caspian ship for a Black Sea cargo.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        Mockito.when(areas.ballastDays(3L, 1L)).thenReturn(OptionalDouble.empty());
        Mockito.when(areas.byId(3L)).thenReturn(new TradeAreaGraph.Area(3L, "CASP", "Caspian Sea", null, null, 0, null));
        Mockito.when(areas.byId(1L)).thenReturn(new TradeAreaGraph.Area(1L, "BSEA", "Black Sea", null, null, 0, null));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(caspian);
        p.setOpenFrom(LocalDate.of(2026, 9, 1));

        MatchScorer.Result r = MatchScorer.score(c, p, areas, TODAY);
        assertThat(r.ruledOut()).isTrue();
        assertThat(check(r, "timing").detail()).contains("too far to consider");
    }

    @Test
    void stillMatchesACargoWhoseLaycanIsPleaseAdvise() {
        // Half the enquiries in this mailbox say "laycan: please advise". Refusing to match
        // them would refuse the ones most in need of tonnage.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        c.setLaycanText("Please advise suitable open tonnage");
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(6));
        Mockito.when(areas.byId(Mockito.anyLong()))
                .thenReturn(new TradeAreaGraph.Area(1L, "X", "X", null, null, 0, null));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(wmed);
        p.setOpenFrom(LocalDate.of(2026, 9, 1));

        MatchScorer.Result r = MatchScorer.score(c, p, areas, TODAY);
        assertThat(r.ruledOut()).isFalse();
        assertThat(check(r, "timing").detail()).contains("no cancelling date");
    }

    // ----------------------------------------------------------------- score

    @Test
    void doesNotRewardAShipForSomethingTheCargoNeverAskedFor() {
        // A cargo with no draft limit must not score a shallow ship above a deep one: the
        // criterion is not applicable and drops out of both halves of the fraction.
        Cargo c = cargo();
        c.setQuantityMin(new BigDecimal("3000"));

        MatchScorer.Result shallow = score(c, position(vessel(v -> {
            v.setDeadweightCargoCapacity(new BigDecimal("6100"));
            v.setMaximumDraft(new BigDecimal("4.5"));
        })));
        MatchScorer.Result deep = score(c, position(vessel(v -> {
            v.setDeadweightCargoCapacity(new BigDecimal("6100"));
            v.setMaximumDraft(new BigDecimal("9.5"));
        })));

        assertThat(shallow.score()).isEqualTo(deep.score()).isEqualTo(100);
    }

    @Test
    void scoresAVerifiedShipAboveAnUnknownOne() {
        Cargo c = cargo();
        c.setQuantityMin(new BigDecimal("3000"));
        c.setRequiresGrainFitted(true);

        MatchScorer.Result known = score(c, position(vessel(v -> {
            v.setDeadweightCargoCapacity(new BigDecimal("6100"));
            v.setGrainFitted(true);
        })));
        MatchScorer.Result unknown = score(c, position(vessel(v -> {
            v.setDeadweightCargoCapacity(new BigDecimal("6100"));
            v.setGrainFitted(null);
        })));

        assertThat(known.score()).isGreaterThan(unknown.score());
        assertThat(unknown.ruledOut()).isFalse();
    }

    // ------------------------------------------------------------- fixtures

    private MatchScorer.Result score(Cargo c, VesselPosition p) {
        return MatchScorer.score(c, p, areas, TODAY);
    }

    private static MatchScorer.Check check(MatchScorer.Result r, String code) {
        return r.checks().stream().filter(c -> c.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no check " + code + " in " + r.checks()));
    }

    private static Cargo cargo() {
        Cargo c = new Cargo();
        c.setCommodity("Wheat");
        return c;
    }

    private static Vessel vessel(java.util.function.Consumer<Vessel> tweak) {
        Vessel v = new Vessel();
        v.setName("TEST");
        tweak.accept(v);
        return v;
    }

    private static VesselPosition position(Vessel v) {
        VesselPosition p = new VesselPosition();
        p.setVessel(v);
        return p;
    }

    private static TradeArea area(Long id, String code) {
        TradeArea a = new TradeArea();
        a.setId(id);
        a.setCode(code);
        a.setName(code);
        return a;
    }
}
