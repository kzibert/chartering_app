package com.chartering.service;

import com.chartering.model.Cargo;
import com.chartering.model.Port;
import com.chartering.model.Vessel;
import com.chartering.model.VesselPosition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Whether one ship suits one cargo, and why.
 *
 * <p><b>Three verdicts, not two.</b> Every test comes back PASS, FAIL or UNKNOWN, and the
 * third is the one that makes this usable on real data. Half this fleet has no gear
 * recorded and two thousand hulls have no DWCC; a scorer that read "not on file" as "does
 * not fit" would rule out most of the tonnage on the desk, and one that read it as "fits"
 * would offer ships nobody had checked. UNKNOWN says so on screen and costs the pair points
 * without excluding it.
 *
 * <p><b>A FAIL rules the pair out; an UNKNOWN never does.</b> FAIL means the data we hold
 * says she does not fit — she is gearless and the cargo needs gear, her draft is deeper than
 * the berth, she is three times the size of the parcel. That is a real answer and the pair
 * should not be proposed. Missing data is not an answer.
 *
 * <p><b>The score is what fraction of the applicable weight was earned.</b> Criteria the
 * cargo says nothing about are not applicable and drop out of both halves of the fraction: a
 * cargo with no draft limit does not reward a ship for being shallow. Criteria it does state
 * but the vessel cannot answer stay in the denominator, which is what makes a
 * well-documented hull outrank an unknown one carrying the same guesses.
 *
 * <p><b>A check can be part right.</b> Most cannot — she is grain-fitted or she is not — and
 * for those {@link Check#credit()} is one or nothing and reads as the verdict does. Two are
 * graded, and they are the two that decide the order of a list: intake, where a ship 92% full
 * and a ship 60% full both pass, and ballast, where a ship two days away and one nine days
 * away both make the laycan. Saying they pass without saying which is better is the difference
 * between a list sorted by how well the ship fits and a list sorted by how many boxes were
 * ticked.
 *
 * <p>Nothing here is stored. A score goes stale the moment a position or a cargo moves, and
 * a table of them would need invalidating on every write in the feature — for a calculation
 * that runs in microseconds.
 */
public final class MatchScorer {

    private MatchScorer() {
    }

    public enum Verdict { PASS, FAIL, UNKNOWN }

    /**
     * One test and its answer.
     *
     * @param credit what share of {@code weight} this check earned, 0 to 1. A PASS is 1 and
     *               anything else is 0 unless the check says otherwise, which only the intake
     *               check does.
     * @param detail what to show a broker — always the actual figures, never "failed size
     *               check". The point of the screen is that the reason can be argued with.
     */
    public record Check(String code, String label, Verdict verdict, int weight, double credit,
                        String detail) {

        /** The ordinary shape: right or wrong, nothing in between. */
        public Check(String code, String label, Verdict verdict, int weight, String detail) {
            this(code, label, verdict, weight, verdict == Verdict.PASS ? 1 : 0, detail);
        }
    }

    /**
     * The ballast leg, and where the figure came from.
     *
     * @param fromPorts true when both ends named a berth the network could place, so the
     *                  distance is a route rather than a broker's round figure between two
     *                  waters. Worth showing: it is the difference between "about six days"
     *                  and "1,214 miles through the Bosphorus".
     * @param via       the narrows on the way, when there was a route. Empty otherwise.
     */
    public record Ballast(double days, Double distanceNm, List<String> via, boolean fromPorts,
                          String from, String to) {
    }

    /**
     * @param ruledOut        any check FAILed
     * @param ballast         the leg from where she is free to where the cargo loads, when
     *                        both ends are known and something connects them
     * @param earliestArrival when she could be at the load port, on that leg
     */
    public record Result(int score,
                         List<Check> checks,
                         boolean ruledOut,
                         Ballast ballast,
                         LocalDate earliestArrival) {

        public long unknownCount() {
            return checks.stream().filter(c -> c.verdict() == Verdict.UNKNOWN).count();
        }

        public List<Check> failures() {
            return checks.stream().filter(c -> c.verdict() == Verdict.FAIL).toList();
        }

        public Double ballastDays() {
            return ballast == null ? null : ballast.days();
        }
    }

    // The weights. Size, intake and where she is are what a broker asks first and they are
    // worth more than everything else together; the fittings are real knockouts but a small
    // part of "how good is this fit" once they have passed.
    //
    // Position is two of these rather than one. The laycan is the larger half because missing
    // it ends the conversation, but the ballast carries real weight of its own: it is what
    // separates two ships that both make the date, and it is the only thing that can order a
    // list at all when the enquiry says "laycan: please advise".
    private static final int W_SIZE = 30;
    private static final int W_TIMING = 20;
    private static final int W_BALLAST = 15;
    private static final int W_INTAKE = 20;
    private static final int W_CUBIC = 10;
    private static final int W_DRAFT = 10;
    private static final int W_GEAR = 10;
    private static final int W_FITTING = 5;
    private static final int W_AGE = 5;

    /** Cubic feet in a cubic metre, for comparing a stowage factor against a grain capacity. */
    private static final BigDecimal CBFT_PER_M3 = new BigDecimal("35.3147");

    public static Result score(Cargo cargo, VesselPosition position, MatchContext ctx) {
        Vessel v = position.getVessel();
        List<Check> checks = new ArrayList<>();

        BigDecimal capacity = cargoCapacity(v);
        checks.add(sizeCheck(cargo, v, capacity));
        checks.add(intakeCheck(cargo, capacity, ctx.tuning()));
        checks.add(cubicCheck(cargo, v));
        checks.add(draftCheck(cargo, v));
        checks.add(gearCheck(cargo, v));
        checks.add(fittingCheck("grain_fitted", "Grain fitted",
                cargo.getRequiresGrainFitted(), v.getGrainFitted()));
        checks.add(fittingCheck("imo_fitted", "IMO fitted",
                cargo.getRequiresImoFitted(), v.getImoFitted()));
        checks.add(ageCheck(cargo, v, ctx.today()));

        Timing timing = timing(cargo, position, ctx);
        checks.add(timing.ballastCheck());
        checks.add(timing.laycanCheck());

        checks.removeIf(java.util.Objects::isNull);

        int applicable = checks.stream().mapToInt(Check::weight).sum();
        double earned = checks.stream().mapToDouble(c -> c.credit() * c.weight()).sum();
        // Nothing applicable means the cargo states no requirement this scorer can test. That
        // is not a perfect match and it is not a bad one; 0 sorts it below anything actually
        // verified, and the empty check list on screen says why.
        int score = applicable == 0 ? 0 : (int) Math.round(100 * earned / applicable);
        boolean ruledOut = checks.stream().anyMatch(c -> c.verdict() == Verdict.FAIL);

        return new Result(score, List.copyOf(checks), ruledOut,
                timing.ballast(), timing.arrival());
    }

    // ------------------------------------------------------------------ size

    /**
     * What she can actually lift: DWCC where it is on file, else DWT.
     *
     * <p>0 means unknown in both columns, as everywhere else in this schema — hence the
     * signum test rather than a null check.
     */
    static BigDecimal cargoCapacity(Vessel v) {
        BigDecimal dwcc = v.getDeadweightCargoCapacity();
        if (dwcc != null && dwcc.signum() > 0) return dwcc;
        BigDecimal dwt = v.getDeadweightTonnage();
        return dwt != null && dwt.signum() > 0 ? dwt : null;
    }

    /**
     * Can she lift it, and is she the size the charterer asked for?
     *
     * <p>Two questions in one check, because they are one question to a broker. The quantity
     * test uses the low end of the cargo's range — a 25,000 +/- 10% cargo is liftable by a
     * ship that can take 22,500, and that tolerance exists precisely so a slightly smaller
     * hull can work.
     *
     * <p>A stated DWT range is tested against her deadweight rather than her cargo capacity:
     * "abt 28,000-35,000 DWT" is a description of the class of ship wanted, and DWT is the
     * figure that class is named by.
     *
     * <p>Only the floor is here. Whether she is too <em>big</em> is a different question with
     * a different answer — see {@link #intakeCheck}, and the reason the two are apart is that
     * a ship can lift a parcel she should never be offered for.
     */
    private static Check sizeCheck(Cargo c, Vessel v, BigDecimal capacity) {
        BigDecimal wanted = c.getQuantityMin() != null ? c.getQuantityMin() : c.getQuantity();
        boolean statesRange = c.getMinDwt() != null || c.getMaxDwt() != null;
        if (wanted == null && !statesRange) return null;

        if (capacity == null) {
            return new Check("size", "Size", Verdict.UNKNOWN, W_SIZE,
                    "No deadweight or cargo capacity on file for her");
        }

        if (wanted != null && capacity.compareTo(wanted) < 0) {
            return new Check("size", "Size", Verdict.FAIL, W_SIZE,
                    "Lifts %s, cargo needs at least %s".formatted(round(capacity), round(wanted)));
        }

        if (statesRange) {
            BigDecimal dwt = v.getDeadweightTonnage();
            if (dwt == null || dwt.signum() == 0) {
                return new Check("size", "Size", Verdict.UNKNOWN, W_SIZE,
                        "Charterer wants %s-%s dwt; no deadweight on file for her"
                                .formatted(round(c.getMinDwt()), round(c.getMaxDwt())));
            }
            if (c.getMinDwt() != null && dwt.compareTo(c.getMinDwt()) < 0) {
                return new Check("size", "Size", Verdict.FAIL, W_SIZE,
                        "%s dwt, charterer wants at least %s".formatted(round(dwt), round(c.getMinDwt())));
            }
            if (c.getMaxDwt() != null && dwt.compareTo(c.getMaxDwt()) > 0) {
                return new Check("size", "Size", Verdict.FAIL, W_SIZE,
                        "%s dwt, charterer wants at most %s".formatted(round(dwt), round(c.getMaxDwt())));
            }
        }

        String detail = wanted != null
                ? "Lifts %s, cargo needs %s".formatted(round(capacity), round(wanted))
                : "%s dwt, inside the %s-%s wanted"
                        .formatted(round(v.getDeadweightTonnage()), round(c.getMinDwt()), round(c.getMaxDwt()));
        return new Check("size", "Size", Verdict.PASS, W_SIZE, detail);
    }

    /**
     * Does the cargo fill her?
     *
     * <p><b>This is the check that stops a 14,000-tonner being offered for a 4,000-tonne
     * parcel</b>, and the reason it is needed is that the size check has no objection: she
     * can lift it easily, which is exactly the problem. Freight is earned by the tonne and
     * paid for by the ship, so a hull sailing 29% full earns 29% of what she costs to run.
     * No owner takes it and no broker should have to scroll past the offer.
     *
     * <p><b>Measured against the most the cargo could offer</b>, not the least. A "25,000 MT
     * +/- 10%" enquiry will load 27,500 into a ship that can take it, because the option is
     * the charterer's; testing the low end would call a good fit a bad one on a tolerance
     * that exists to help. Where no upper figure exists at all — an enquiry that says only
     * "min 3,000 mt" — the check does not apply, because a floor is not a bound on the
     * intake and ruling a ship out against one would be ruling her out against a number the
     * cargo never claimed.
     *
     * <p><b>Silent when the charterer stated a size range.</b> "Abt 28-35,000 DWT" is their
     * own answer to this question, the size check has already tested it, and a ratio arguing
     * with it would rule out a ship the charterer asked for by name.
     *
     * <p>Two figures rather than one, and the space between them is the point. Below the
     * floor the pairing is ruled out; above the ideal it scores full marks; between them it
     * passes and earns the share of the distance it has come — because part cargoes are real
     * and this desk does them, and a 60%-full ship is an argument rather than a mistake.
     */
    private static Check intakeCheck(Cargo c, BigDecimal capacity, MatchSettings.Values t) {
        if (c.getMinDwt() != null || c.getMaxDwt() != null) return null;
        // Her own figure is missing, and the size check has already said so. Saying it twice
        // would charge one gap in the record against two criteria.
        if (capacity == null) return null;

        BigDecimal intake = c.getQuantityMax() != null ? c.getQuantityMax() : c.getQuantity();
        if (intake == null || intake.signum() <= 0) return null;

        // Capped: a cargo bigger than the ship means she loads full and down, which is the
        // best fit there is rather than an impossible one over a hundred percent.
        double filled = Math.min(intake.doubleValue() / capacity.doubleValue(), 1.0);
        int percent = (int) Math.round(filled * 100);
        String figures = "Cargo up to %s against %s she can lift - %d%% full"
                .formatted(round(intake), round(capacity), percent);

        if (percent < t.minUtilisationPercent()) {
            return new Check("intake", "Intake", Verdict.FAIL, W_INTAKE,
                    "%s, under the %d%% this desk will offer"
                            .formatted(figures, t.minUtilisationPercent()));
        }

        double floor = t.minUtilisationPercent() / 100.0;
        double ideal = t.idealUtilisationPercent() / 100.0;
        double credit = filled >= ideal || ideal <= floor
                ? 1
                : (filled - floor) / (ideal - floor);
        return new Check("intake", "Intake", Verdict.PASS, W_INTAKE, credit, figures);
    }

    /**
     * Does it cube out before it weighs out?
     *
     * <p>Only asked when the cargo gives a stowage factor, which is rare and is exactly why
     * it is worth asking when it does: 5,000 tonnes of HBI and 5,000 tonnes of grain want
     * very different holds, and deadweight alone cannot tell them apart.
     */
    private static Check cubicCheck(Cargo c, Vessel v) {
        BigDecimal sf = c.getStowageFactor();
        BigDecimal quantity = c.getQuantityMin() != null ? c.getQuantityMin() : c.getQuantity();
        if (sf == null || sf.signum() <= 0 || quantity == null) return null;

        BigDecimal grain = v.getGrainCapacityM3();
        if (grain == null || grain.signum() == 0) {
            return new Check("cubic", "Cubic", Verdict.UNKNOWN, W_CUBIC,
                    "No grain capacity on file for her");
        }
        BigDecimal neededM3 = quantity.multiply(sf).divide(CBFT_PER_M3, 0, RoundingMode.CEILING);
        if (grain.compareTo(neededM3) < 0) {
            return new Check("cubic", "Cubic", Verdict.FAIL, W_CUBIC,
                    "Needs about %s m3 at sf %s, she holds %s".formatted(neededM3, sf, round(grain)));
        }
        return new Check("cubic", "Cubic", Verdict.PASS, W_CUBIC,
                "Needs about %s m3 at sf %s, she holds %s".formatted(neededM3, sf, round(grain)));
    }

    private static Check draftCheck(Cargo c, Vessel v) {
        if (c.getMaxDraft() == null || c.getMaxDraft().signum() <= 0) return null;
        BigDecimal draft = v.getMaximumDraft();
        if (draft == null || draft.signum() == 0) {
            return new Check("draft", "Draft", Verdict.UNKNOWN, W_DRAFT, "No draft on file for her");
        }
        if (draft.compareTo(c.getMaxDraft()) > 0) {
            return new Check("draft", "Draft", Verdict.FAIL, W_DRAFT,
                    "Draws %sm, berth takes %sm".formatted(draft, c.getMaxDraft()));
        }
        return new Check("draft", "Draft", Verdict.PASS, W_DRAFT,
                "Draws %sm, berth takes %sm".formatted(draft, c.getMaxDraft()));
    }

    /**
     * Gear, asked one way only.
     *
     * <p>A cargo that requires gear rules out a gearless ship. A cargo that says gear is
     * <em>not</em> required does not rule out a geared one — cranes she does not need cost
     * the charterer nothing — so that case passes rather than failing, which is why this is
     * not the symmetric test it looks like.
     */
    private static Check gearCheck(Cargo c, Vessel v) {
        if (!Boolean.TRUE.equals(c.getRequiresGeared())) return null;
        if (v.getGeared() == null) {
            return new Check("gear", "Gear", Verdict.UNKNOWN, W_GEAR,
                    "Cargo wants gear; nothing on file about hers");
        }
        if (!v.getGeared()) {
            return new Check("gear", "Gear", Verdict.FAIL, W_GEAR, "Gearless, cargo wants gear");
        }
        String gear = v.getGearDescription();
        return new Check("gear", "Gear", Verdict.PASS, W_GEAR,
                gear == null || gear.isBlank() ? "Geared" : "Geared - " + gear);
    }

    /** Grain-fitted and IMO-fitted, which behave identically. */
    private static Check fittingCheck(String code, String label, Boolean required, Boolean fitted) {
        if (!Boolean.TRUE.equals(required)) return null;
        if (fitted == null) {
            return new Check(code, label, Verdict.UNKNOWN, W_FITTING,
                    "Cargo wants it; nothing on file about her");
        }
        return fitted
                ? new Check(code, label, Verdict.PASS, W_FITTING, "Fitted")
                : new Check(code, label, Verdict.FAIL, W_FITTING, "Not fitted, cargo wants it");
    }

    private static Check ageCheck(Cargo c, Vessel v, LocalDate today) {
        if (c.getMaxAgeYears() == null) return null;
        if (v.getYearBuilt() == null || v.getYearBuilt() == 0) {
            return new Check("age", "Age", Verdict.UNKNOWN, W_AGE, "No build year on file for her");
        }
        int age = today.getYear() - v.getYearBuilt();
        if (age > c.getMaxAgeYears()) {
            return new Check("age", "Age", Verdict.FAIL, W_AGE,
                    "Built %d, %d years old against a %d-year limit"
                            .formatted(v.getYearBuilt(), age, c.getMaxAgeYears()));
        }
        return new Check("age", "Age", Verdict.PASS, W_AGE,
                "Built %d, %d years old".formatted(v.getYearBuilt(), age));
    }

    // ---------------------------------------------------------------- timing

    /**
     * @param ballastCheck how far she has to come, graded and capped
     * @param laycanCheck  whether she can be there by the cancelling date, and only that
     */
    private record Timing(Check ballastCheck, Check laycanCheck, Ballast ballast,
                          LocalDate arrival) {

        static Timing none() {
            return new Timing(null, null, null, null);
        }

        static Timing ballastOnly(Check ballast) {
            return new Timing(ballast, null, null, null);
        }
    }

    /**
     * Where she is, and whether that works - two checks, because they are two arguments.
     *
     * <p><b>The distance and the deadline are not one question.</b> A ship five days away and
     * one fifteen days away both make a cancelling date three weeks out, and asking only "can
     * she make it" scores them identically - which is how a list ends up leading with a hull
     * on the wrong side of the Med. The ballast leg is a cost the owner carries whether or not
     * there is a laycan to meet, so it is scored on its own and graded. It is also what orders
     * the list when the cargo says "laycan: please advise", which is half the enquiries in
     * this mailbox and exactly where nothing else can order it.
     *
     * <p><b>Two sources for the leg, asked in order of what they can tell apart.</b> Where
     * both ends name a berth this database has placed, the sea network answers in miles and
     * straits: Rostov and Constanza are both "Black Sea" and two days apart. Where either end
     * is only a water - which is most of the mail, because a circular says "SPOT AT MARMARA" -
     * the trade-area table answers in a broker's round days, which is what it was built for
     * and is still the right answer to a question asked that coarsely.
     *
     * <p>Four different absences are four different answers, and collapsing them would be the
     * easiest way to make this screen untrustworthy:
     *
     * <ul>
     *   <li>The cargo names no load point - nothing to test, neither check applies.
     *   <li>Her position names no area - UNKNOWN. Somebody wrote a position we could not
     *       resolve, and that is worth seeing rather than guessing past.
     *   <li>Both known but nothing connects them - FAIL. Neither the network nor the distance
     *       table joins them, and both are sparse on purpose, so a missing pair means too far
     *       to consider. Saying "unknown" would invite offering a Caspian ship for a Med cargo.
     *   <li>Reachable, but the cargo gives no cancelling date - the laycan check does not apply
     *       and drops out of both halves of the score, the same as a cargo stating no draft
     *       limit. It used to be a PASS worth full marks, which paid a ship the whole of the
     *       position weight for meeting a deadline nobody had set.
     * </ul>
     */
    private static Timing timing(Cargo c, VesselPosition p, MatchContext ctx) {
        Port loadPort = c.getLoadPort();
        Port openPort = p.getOpenPort();

        Ballast leg = portLeg(openPort, loadPort, ctx).orElse(null);
        if (leg == null) {
            Long cargoArea = areaOf(loadPort == null ? null : loadPort.getTradeArea(), c.getLoadArea());
            if (cargoArea == null) return Timing.none();

            Long openArea = areaOf(openPort == null ? null : openPort.getTradeArea(), p.getOpenArea());
            if (openArea == null) {
                return Timing.ballastOnly(new Check("ballast", "Ballast", Verdict.UNKNOWN,
                        W_BALLAST, "Her position names no area we could resolve"));
            }

            OptionalDouble days = ctx.areas().ballastDays(openArea, cargoArea);
            String from = nameOf(ctx.areas(), openArea);
            String to = nameOf(ctx.areas(), cargoArea);
            if (days.isEmpty()) {
                return Timing.ballastOnly(new Check("ballast", "Ballast", Verdict.FAIL, W_BALLAST,
                        "Nothing on file connects %s and %s - too far to consider"
                                .formatted(from, to)));
            }
            leg = new Ballast(days.getAsDouble(), null, List.of(), false, from, to);
        }

        Check ballastCheck = ballastCheck(leg, c, ctx.tuning());
        if (ballastCheck.verdict() == Verdict.FAIL) {
            // Past the limit the pairing is out, and an arrival date under it would be an
            // answer to a question that stopped being asked. The leg still travels, because
            // the row behind "show ruled out" is where somebody argues with the limit.
            return new Timing(ballastCheck, null, leg, null);
        }

        Departure departure = departure(p, ctx.today());
        LocalDate arrival = departure == null
                ? null
                : departure.sails().plusDays((long) Math.ceil(leg.days()));

        if (c.getLaycanTo() == null) {
            return new Timing(ballastCheck, null, leg, arrival);
        }
        if (arrival == null) {
            // She is on somebody's list with no dates against her at all. That is a gap in the
            // record rather than an answer, and it is the one thing this check can be asked
            // and cannot say.
            return new Timing(ballastCheck,
                    new Check("timing", "Laycan", Verdict.UNKNOWN, W_TIMING,
                            "Cancelling %s; her position gives no dates".formatted(c.getLaycanTo())),
                    leg, null);
        }

        String sailing = departure.sailsToday()
                ? "Was open %s, so counted from today".formatted(departure.free())
                : "Free %s".formatted(departure.free());
        if (arrival.isAfter(c.getLaycanTo())) {
            return new Timing(ballastCheck,
                    new Check("timing", "Laycan", Verdict.FAIL, W_TIMING,
                            "%s - arrives %s, cancelling %s"
                                    .formatted(sailing, arrival, c.getLaycanTo())),
                    leg, arrival);
        }
        return new Timing(ballastCheck,
                new Check("timing", "Laycan", Verdict.PASS, W_TIMING,
                        "%s - arrives %s, laycan to %s"
                                .formatted(sailing, arrival, c.getLaycanTo())),
                leg, arrival);
    }

    /**
     * How far she has to come, and whether that is further than this cargo is worth.
     *
     * <p>Two figures rather than one, the same shape the intake check uses and for the same
     * reason. Under the ideal she is near enough that the leg costs nothing worth scoring;
     * beyond the limit the pairing is ruled out; between them she passes and earns the share
     * of the distance she has come, because a ship six days away is an argument rather than a
     * mistake. Linear in between, which is as much shape as an estimate built on an assumed
     * speed can honestly carry.
     *
     * <p><b>The limit is the cargo's own where it states one.</b> A desk-wide figure is the
     * right default and the wrong answer for the parcel somebody would ballast the Atlantic
     * for, or the one nobody would cross the Med for - so {@code cargoes.max_ballast_days}
     * overrides it per enquiry and the detail line says which figure it used, because the
     * limit is exactly the thing a broker will want to disagree with.
     *
     * <p>A FAIL here is a desk policy rather than a fact about the hull, which is the one
     * place this scorer's usual reading of FAIL is stretched - and it is stretched in the same
     * direction the intake floor already stretches it. She can physically make the passage;
     * she is simply not tonnage for this cargo, and a broker should not have to scroll past
     * her to find one that is.
     */
    private static Check ballastCheck(Ballast leg, Cargo c, MatchSettings.Values t) {
        int limit = c.getMaxBallastDays() != null ? c.getMaxBallastDays() : t.maxBallastDays();
        int ideal = Math.min(t.idealBallastDays(), limit);
        String whose = c.getMaxBallastDays() != null
                ? "this cargo will take"
                : "this desk will ballast";

        if (leg.days() > limit) {
            return new Check("ballast", "Ballast", Verdict.FAIL, W_BALLAST,
                    "%s - past the %d days %s".formatted(describe(leg), limit, whose));
        }
        double credit = leg.days() <= ideal || limit <= ideal
                ? 1
                : (limit - leg.days()) / (double) (limit - ideal);
        return new Check("ballast", "Ballast", Verdict.PASS, W_BALLAST, credit, describe(leg));
    }

    /**
     * When she can actually start the leg.
     *
     * @param free       her last free day as reported, which may be weeks ago
     * @param sails      the day the ballast is counted from
     * @param sailsToday {@code free} has passed, so {@code sails} is today instead
     */
    private record Departure(LocalDate free, LocalDate sails, boolean sailsToday) {
    }

    /**
     * Her last free day, or today if that day has gone.
     *
     * <p><b>Her last, not her first:</b> a ship open 1/3 September is not sailing on the 1st,
     * and the optimistic end would put ships on lists they cannot make.
     *
     * <p><b>And never a day already past.</b> Positions are append-only and a LIVE row is not
     * withdrawn when its dates run out, so a list swept three weeks ago still reports her open
     * 25/28 August. Counting the ballast from the 28th printed "Could present 2 September" on
     * a screen being read on the 9th - a date in the past, offered as a promise - and, worse,
     * passed her for cancelling dates she cannot reach, because the arrival it compared them
     * against was one nobody could sail to. Whatever the list said, the earliest she can leave
     * is today.
     */
    private static Departure departure(VesselPosition p, LocalDate today) {
        LocalDate free = p.getOpenTo() != null ? p.getOpenTo() : p.getOpenFrom();
        if (free == null) return null;
        return free.isBefore(today)
                ? new Departure(free, today, true)
                : new Departure(free, free, false);
    }

    /**
     * The passage between two berths, in days, when the network can draw it.
     *
     * <p>Empty for a port with no gateway, a port with no coordinates, or a pair the network
     * genuinely does not join — and the caller falls back to the area table in all three,
     * because the fallback is what this feature had before the network existed and is never
     * worse than it.
     */
    private static Optional<Ballast> portLeg(Port open, Port load, MatchContext ctx) {
        if (open == null || load == null) return Optional.empty();
        return ctx.routes().between(open, load).map(route -> new Ballast(
                roundHalf(ctx.tuning().daysFor(route.distanceNm(), route.delayHours())),
                route.distanceNm(), route.via(), true, open.getName(), load.getName()));
    }

    private static String describe(Ballast leg) {
        if (!leg.fromPorts()) {
            return leg.days() == 0
                    ? "Already in %s".formatted(leg.to())
                    : "%s to %s, about %s days".formatted(leg.from(), leg.to(), trim(leg.days()));
        }
        String via = leg.via().isEmpty() ? "" : " via " + String.join(", ", leg.via());
        return "%s to %s, %s nm%s - about %s days"
                .formatted(leg.from(), leg.to(), miles(leg.distanceNm()), via, trim(leg.days()));
    }

    /** A port's own area wins over one typed by hand — it was looked up rather than guessed. */
    private static Long areaOf(com.chartering.model.TradeArea portArea,
                               com.chartering.model.TradeArea stated) {
        if (portArea != null) return portArea.getId();
        return stated != null ? stated.getId() : null;
    }

    private static String nameOf(TradeAreaGraph areas, Long id) {
        TradeAreaGraph.Area a = areas.byId(id);
        return a == null ? "?" : a.code();
    }

    private static String round(BigDecimal value) {
        return value == null ? "?" : value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String miles(Double nm) {
        return nm == null ? "?" : String.format("%,d", Math.round(nm));
    }

    private static String trim(double days) {
        return days == Math.rint(days) ? String.valueOf((long) days) : String.valueOf(days);
    }

    /**
     * Half a day, which is as fine as a ballast estimate is worth quoting.
     *
     * <p>The miles are computed and the speed is a working assumption; printing 5.3 days off
     * a number nobody measured would claim a precision the calculation does not have. The
     * arrival date rounds up from here regardless, so the half day never buys anybody an
     * extra one.
     */
    private static double roundHalf(double days) {
        return Math.round(days * 2) / 2.0;
    }
}
