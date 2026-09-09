package com.chartering.service;

import com.chartering.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    // The real vocabulary and the real sea network are database tables; these tests only
    // need them to answer two questions each, so they are stubbed rather than loaded. The
    // routes mock answers Optional.empty() to everything by default, which is exactly the
    // "neither end named a placed berth" case that sends the scorer to the area table -- the
    // path most of this mailbox's positions actually take.
    private final TradeAreaGraph areas = Mockito.mock(TradeAreaGraph.class);
    private final SeaRouteGraph routes = Mockito.mock(SeaRouteGraph.class);

    private MatchContext ctx() {
        return new MatchContext(areas, routes, MatchSettings.defaults(), TODAY);
    }

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
        p.setOpenFrom(TODAY.plusDays(6));

        MatchScorer.Result r = MatchScorer.score(c, p, ctx());
        assertThat(check(r, "ballast").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
        assertThat(check(r, "ballast").credit()).isEqualTo(1);
        assertThat(r.ballastDays()).isZero();
    }

    @Test
    void rulesOutAShipThatCannotMakeTheCancellingDate() {
        Cargo c = cargo();
        c.setLoadArea(bsea);
        c.setLaycanTo(TODAY.plusDays(10));
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(6));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(wmed);
        p.setOpenFrom(TODAY.plusDays(6));
        p.setOpenTo(TODAY.plusDays(7));

        MatchScorer.Result r = MatchScorer.score(c, p, ctx());

        // Free on the seventh day, six days' ballast, so the thirteenth - three past cancelling.
        assertThat(r.ruledOut()).isTrue();
        assertThat(r.earliestArrival()).isEqualTo(TODAY.plusDays(13));
        assertThat(check(r, "timing").verdict()).isEqualTo(MatchScorer.Verdict.FAIL);
        assertThat(check(r, "timing").detail()).contains("arrives " + TODAY.plusDays(13));
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
        p.setOpenFrom(TODAY.plusDays(6));
        p.setOpenTo(TODAY.plusDays(8));

        assertThat(MatchScorer.score(c, p, ctx()).earliestArrival())
                .isEqualTo(TODAY.plusDays(10));
    }

    @Test
    void neverPresentsHerOnADayThatHasAlreadyGone() {
        // The bug this test exists for: a LIVE position is not withdrawn when its dates run
        // out, so a list swept three weeks ago still reports her open then. Counting the
        // ballast from a date in the past printed "Could present" in the past.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(4));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(wmed);
        p.setOpenFrom(TODAY.minusDays(15));
        p.setOpenTo(TODAY.minusDays(12));

        MatchScorer.Result r = MatchScorer.score(c, p, ctx());

        assertThat(r.earliestArrival()).isEqualTo(TODAY.plusDays(4));
    }

    @Test
    void doesNotPassAStaleShipForALaycanSheCannotReach() {
        // The same bug with teeth. Open twelve days ago, four days' ballast: counted from
        // her stale date she "arrives" eight days ago and clears a cancelling date two days
        // out. She cannot sail before today, so she cannot make it at all.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        c.setLaycanTo(TODAY.plusDays(2));
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(4));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(wmed);
        p.setOpenTo(TODAY.minusDays(12));

        MatchScorer.Result r = MatchScorer.score(c, p, ctx());

        assertThat(r.ruledOut()).isTrue();
        assertThat(check(r, "timing").detail()).contains("counted from today");
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
        p.setOpenFrom(TODAY.plusDays(6));

        MatchScorer.Result r = MatchScorer.score(c, p, ctx());
        assertThat(r.ruledOut()).isTrue();
        assertThat(check(r, "ballast").detail()).contains("too far to consider");
    }

    @Test
    void stillMatchesACargoWhoseLaycanIsPleaseAdvise() {
        // Half the enquiries in this mailbox say "laycan: please advise". Refusing to match
        // them would refuse the ones most in need of tonnage - but the laycan check has
        // nothing to test either, so it drops out rather than being paid full marks.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        c.setLaycanText("Please advise suitable open tonnage");
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(6));
        Mockito.when(areas.byId(Mockito.anyLong()))
                .thenReturn(new TradeAreaGraph.Area(1L, "X", "X", null, null, 0, null));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(wmed);
        p.setOpenFrom(TODAY.plusDays(6));

        MatchScorer.Result r = MatchScorer.score(c, p, ctx());
        assertThat(r.ruledOut()).isFalse();
        assertThat(r.checks()).noneMatch(x -> x.code().equals("timing"));
        assertThat(check(r, "ballast").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
    }

    @Test
    void saysUnknownWhenSheIsOnAListWithNoDatesAgainstHer() {
        // A cancelling date is a real question and her record cannot answer it. Passing her
        // used to be worth full marks for a deadline nothing had been tested against.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        c.setLaycanTo(TODAY.plusDays(20));
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(4));
        Mockito.when(areas.byId(Mockito.anyLong()))
                .thenReturn(new TradeAreaGraph.Area(1L, "X", "X", null, null, 0, null));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(wmed);
        p.setOpenText("SPOT");

        MatchScorer.Result r = MatchScorer.score(c, p, ctx());
        assertThat(check(r, "timing").verdict()).isEqualTo(MatchScorer.Verdict.UNKNOWN);
        assertThat(r.ruledOut()).isFalse();
        assertThat(r.earliestArrival()).isNull();
    }

    // --------------------------------------------------------------- ballast

    @Test
    void scoresANearerShipAboveAFartherOneThatBothMakeTheLaycan() {
        // The whole point. Both are there in time and the old scorer called them equal,
        // which is how a list ends up leading with a hull on the wrong side of the Med.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        c.setLaycanTo(TODAY.plusDays(40));
        Mockito.when(areas.byId(Mockito.anyLong()))
                .thenReturn(new TradeAreaGraph.Area(1L, "X", "X", null, null, 0, null));
        Mockito.when(areas.ballastDays(1L, 1L)).thenReturn(OptionalDouble.of(2));
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(9));

        MatchScorer.Result near = MatchScorer.score(c, openIn(bsea), ctx());
        MatchScorer.Result far = MatchScorer.score(c, openIn(wmed), ctx());

        assertThat(near.ruledOut()).isFalse();
        assertThat(far.ruledOut()).isFalse();
        assertThat(check(far, "ballast").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
        assertThat(check(near, "ballast").credit()).isGreaterThan(check(far, "ballast").credit());
        assertThat(near.score()).isGreaterThan(far.score());
    }

    @Test
    void ordersTheListEvenWhenTheCargoNamesNoLaycanAtAll() {
        // "Laycan: please advise" leaves the ballast as the only thing that can order it.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        Mockito.when(areas.byId(Mockito.anyLong()))
                .thenReturn(new TradeAreaGraph.Area(1L, "X", "X", null, null, 0, null));
        Mockito.when(areas.ballastDays(1L, 1L)).thenReturn(OptionalDouble.of(1));
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(11));

        assertThat(MatchScorer.score(c, openIn(bsea), ctx()).score())
                .isGreaterThan(MatchScorer.score(c, openIn(wmed), ctx()).score());
    }

    @Test
    void rulesOutALegLongerThanTheDeskWillBallast() {
        Cargo c = cargo();
        c.setLoadArea(bsea);
        Mockito.when(areas.byId(Mockito.anyLong()))
                .thenReturn(new TradeAreaGraph.Area(1L, "X", "X", null, null, 0, null));
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(19));

        MatchScorer.Result r = MatchScorer.score(c, openIn(wmed), ctx());

        assertThat(r.ruledOut()).isTrue();
        assertThat(check(r, "ballast").detail())
                .contains("past the 15").contains("this desk will ballast");
    }

    @Test
    void letsOneCargoDisagreeWithTheDeskAboutHowFarIsTooFar() {
        // The per-cargo override. Nine days is inside the desk-wide fifteen and outside the
        // five this enquiry is worth, and the detail line says whose figure ruled her out.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        c.setMaxBallastDays((short) 5);
        Mockito.when(areas.byId(Mockito.anyLong()))
                .thenReturn(new TradeAreaGraph.Area(1L, "X", "X", null, null, 0, null));
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(9));

        MatchScorer.Result r = MatchScorer.score(c, openIn(wmed), ctx());

        assertThat(r.ruledOut()).isTrue();
        assertThat(check(r, "ballast").detail())
                .contains("past the 5").contains("this cargo will take");
    }

    @Test
    void keepsAShipTheCargoIsWorthReachingFor() {
        // And the other way: a cargo worth crossing an ocean for overrides upwards, and a
        // leg the desk-wide figure would have refused becomes an ordinary pairing.
        Cargo c = cargo();
        c.setLoadArea(bsea);
        c.setMaxBallastDays((short) 30);
        Mockito.when(areas.byId(Mockito.anyLong()))
                .thenReturn(new TradeAreaGraph.Area(1L, "X", "X", null, null, 0, null));
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(19));

        MatchScorer.Result r = MatchScorer.score(c, openIn(wmed), ctx());

        assertThat(r.ruledOut()).isFalse();
        assertThat(check(r, "ballast").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
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


    // ---------------------------------------------------------------- intake

    @Test
    void refusesToOfferAShipThreeTimesTheSizeOfTheParcel() {
        // The rule this check exists for. She lifts it easily, which is exactly the problem:
        // freight is earned by the tonne and the ship is paid for whole, so 4,000 tonnes in a
        // 14,000-tonner earns 29% of what she costs to run and no owner takes it.
        Cargo c = cargo();
        c.setQuantity(new BigDecimal("4000"));

        MatchScorer.Result r = score(c, position(vessel(v ->
                v.setDeadweightTonnage(new BigDecimal("14000")))));

        assertThat(r.ruledOut()).isTrue();
        assertThat(check(r, "intake").verdict()).isEqualTo(MatchScorer.Verdict.FAIL);
        assertThat(check(r, "intake").detail()).contains("29% full").contains("55%");
    }

    @Test
    void measuresTheCargoAtItsUpperEndBecauseTheOptionIsTheCharterers() {
        // 25,000 +/- 10% will load 27,500 into a hull that can take it. Testing the low end
        // would call a full ship a badly used one on a tolerance that exists to help.
        Cargo c = cargo();
        c.setQuantity(new BigDecimal("25000"));
        c.setQuantityMin(new BigDecimal("22500"));
        c.setQuantityMax(new BigDecimal("27500"));

        MatchScorer.Result r = score(c, position(vessel(v ->
                v.setDeadweightCargoCapacity(new BigDecimal("28000")))));

        assertThat(check(r, "intake").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
        assertThat(check(r, "intake").credit()).isEqualTo(1);
    }

    @Test
    void scoresAFullerShipAboveAnEmptierOneWithoutRulingEitherOut() {
        // Between the floor and the ideal a pairing is an argument rather than a mistake -
        // part cargoes are real - so both pass and the score says which is better.
        Cargo c = cargo();
        c.setQuantity(new BigDecimal("6000"));

        MatchScorer.Result full = score(c, position(vessel(v ->
                v.setDeadweightCargoCapacity(new BigDecimal("6500")))));
        MatchScorer.Result loose = score(c, position(vessel(v ->
                v.setDeadweightCargoCapacity(new BigDecimal("9000")))));

        assertThat(full.ruledOut()).isFalse();
        assertThat(loose.ruledOut()).isFalse();
        assertThat(check(loose, "intake").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
        assertThat(check(loose, "intake").credit()).isBetween(0.0, 1.0);
        assertThat(full.score()).isGreaterThan(loose.score());
    }

    @Test
    void staysOutOfItWhenTheChartererStatedTheSizeTheyWant() {
        // "Abt 28-35,000 DWT" is the charterer's own answer to this question and the size
        // check has already tested it. A ratio arguing with it would rule out a ship they
        // asked for by name.
        Cargo c = cargo();
        c.setQuantity(new BigDecimal("20000"));
        c.setMinDwt(new BigDecimal("28000"));
        c.setMaxDwt(new BigDecimal("35000"));

        MatchScorer.Result r = score(c, position(vessel(v ->
                v.setDeadweightTonnage(new BigDecimal("34000")))));

        assertThat(r.checks()).noneMatch(x -> x.code().equals("intake"));
        assertThat(r.ruledOut()).isFalse();
    }

    @Test
    void doesNotRuleOutAgainstAFloorTheCargoNeverClaimed() {
        // "Min 3,000 mt" bounds the cargo from below and says nothing about the most it
        // could be. Reading it as the intake would rule out every ship above 5,500 tonnes
        // for an enquiry that may well fill them.
        Cargo c = cargo();
        c.setQuantityMin(new BigDecimal("3000"));

        MatchScorer.Result r = score(c, position(vessel(v ->
                v.setDeadweightCargoCapacity(new BigDecimal("14000")))));

        assertThat(r.checks()).noneMatch(x -> x.code().equals("intake"));
        assertThat(r.ruledOut()).isFalse();
    }

    // ------------------------------------------------------- the sea network

    @Test
    void measuresTheLegInMilesWhenBothEndsNameABerth() {
        // The whole point of the network: Constanza and Rostov are both "Black Sea" and two
        // days apart, and the area table cannot tell them apart at all.
        Cargo c = cargo();
        Port load = port("Odessa", bsea);
        c.setLoadPort(load);
        c.setLaycanTo(TODAY.plusDays(25));

        VesselPosition p = position(vessel(v -> {}));
        Port open = port("Salerno", wmed);
        p.setOpenPort(open);
        p.setOpenFrom(TODAY.plusDays(6));
        Mockito.when(routes.between(open, load)).thenReturn(java.util.Optional.of(
                new SeaRouteGraph.Route(1500, 14, List.of("Dardanelles", "Bosphorus north"))));

        MatchScorer.Result r = MatchScorer.score(c, p, ctx());

        assertThat(check(r, "timing").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
        assertThat(check(r, "ballast").detail())
                .contains("Salerno to Odessa")
                .contains("1,500 nm")
                .contains("via Dardanelles, Bosphorus north");
        assertThat(r.ballast().distanceNm()).isEqualTo(1500);
        // 1,500 nm at 11.5 kn is 130.4 hours; the strait waits and the two berths add 26 more.
        assertThat(r.ballast().days()).isEqualTo(6.5);
        assertThat(r.earliestArrival()).isEqualTo(TODAY.plusDays(13));
    }

    @Test
    void fallsBackToTheAreaTableWhenTheNetworkCannotPlaceABerth() {
        // A port nobody has given coordinates to is no worse off than it was before the
        // network existed, which is the whole reason the fallback is there.
        Cargo c = cargo();
        c.setLoadPort(port("Somewhere", bsea));
        Mockito.when(routes.between(Mockito.any(), Mockito.any()))
                .thenReturn(java.util.Optional.empty());
        Mockito.when(areas.ballastDays(2L, 1L)).thenReturn(OptionalDouble.of(6));
        Mockito.when(areas.byId(Mockito.anyLong()))
                .thenReturn(new TradeAreaGraph.Area(1L, "X", "X", null, null, 0, null));

        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(wmed);
        p.setOpenFrom(TODAY.plusDays(6));

        MatchScorer.Result r = MatchScorer.score(c, p, ctx());

        assertThat(check(r, "ballast").verdict()).isEqualTo(MatchScorer.Verdict.PASS);
        assertThat(check(r, "ballast").detail()).contains("about 6 days");
        assertThat(r.ballast().distanceNm()).isNull();
        assertThat(r.ballast().fromPorts()).isFalse();
    }

    // ------------------------------------------------------------- fixtures

    private MatchScorer.Result score(Cargo c, VesselPosition p) {
        return MatchScorer.score(c, p, ctx());
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

    /** A hull free in a week, wherever the caller says. */
    private VesselPosition openIn(TradeArea where) {
        VesselPosition p = position(vessel(v -> {}));
        p.setOpenArea(where);
        p.setOpenFrom(TODAY.plusDays(7));
        return p;
    }

    private static VesselPosition position(Vessel v) {
        VesselPosition p = new VesselPosition();
        p.setVessel(v);
        return p;
    }

    private static Port port(String name, TradeArea area) {
        Port p = new Port();
        p.setName(name);
        p.setTradeArea(area);
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
