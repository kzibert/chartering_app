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
 * for those {@link Check#credit()} is one or nothing and reads as the verdict does. Intake is
 * the exception the field exists for: a ship 92% full and a ship 60% full both pass, and
 * saying so without saying which is better would be the difference between a list sorted by
 * how well the ship fits and a list sorted by how many boxes were ticked.
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

    // The weights. Size, intake and timing are what a broker asks first and they are worth
    // more than everything else together; the fittings are real knockouts but a small part of
    // "how good is this fit" once they have passed.
    private static final int W_SIZE = 30;
    private static final int W_TIMING = 30;
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
        checks.add(timing.check());

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

    private record Timing(Check check, Ballast ballast, LocalDate arrival) {
    }

    /**
     * Can she be there in time?
     *
     * <p><b>Two sources, asked in order of what they can tell apart.</b> Where both ends name
     * a berth this database has placed, the sea network answers in miles and straits: Rostov
     * and Constanza are both "Black Sea" and two days apart, and this is the difference
     * between the two. Where either end is only a water — which is most of the mail, because
     * a circular says "SPOT AT MARMARA" — the trade-area table answers in a broker's round
     * days, which is what it was built for and is still the right answer to a question asked
     * that coarsely.
     *
     * <p>Her open date plus that leg is the earliest she can present. If that is after the
     * cancelling date she cannot make it, which is a fact and rules the pair out.
     *
     * <p>Four different absences are four different answers, and collapsing them would be the
     * easiest way to make this screen untrustworthy:
     *
     * <ul>
     *   <li>The cargo names no load point — nothing to test, the check does not apply.
     *   <li>Her position names no area — UNKNOWN. Somebody wrote a position we could not
     *       resolve, and that is worth seeing rather than guessing past.
     *   <li>Both known but nothing connects them — FAIL. Neither the network nor the distance
     *       table joins them, and both are sparse on purpose, so a missing pair means too far
     *       to consider. Saying "unknown" would invite offering a Caspian ship for a Med cargo.
     *   <li>Reachable, but the cargo gives no laycan — PASS, with the leg named. Half the
     *       enquiries in this mailbox say "laycan: please advise", and refusing to match them
     *       would refuse to match the ones most in need of tonnage.
     * </ul>
     */
    private static Timing timing(Cargo c, VesselPosition p, MatchContext ctx) {
        Port loadPort = c.getLoadPort();
        Port openPort = p.getOpenPort();

        Ballast leg = portLeg(openPort, loadPort, ctx).orElse(null);
        if (leg == null) {
            Long cargoArea = areaOf(loadPort == null ? null : loadPort.getTradeArea(), c.getLoadArea());
            if (cargoArea == null) return new Timing(null, null, null);

            Long openArea = areaOf(openPort == null ? null : openPort.getTradeArea(), p.getOpenArea());
            if (openArea == null) {
                return new Timing(new Check("timing", "Position", Verdict.UNKNOWN, W_TIMING,
                        "Her position names no area we could resolve"), null, null);
            }

            OptionalDouble days = ctx.areas().ballastDays(openArea, cargoArea);
            String from = nameOf(ctx.areas(), openArea);
            String to = nameOf(ctx.areas(), cargoArea);
            if (days.isEmpty()) {
                return new Timing(new Check("timing", "Position", Verdict.FAIL, W_TIMING,
                        "Nothing on file connects %s and %s - too far to consider".formatted(from, to)),
                        null, null);
            }
            leg = new Ballast(days.getAsDouble(), null, List.of(), false, from, to);
        }

        // Her latest free day, not her earliest: a ship open 1/3 September is not sailing on
        // the 1st, and using the optimistic end would put ships on lists they cannot make.
        LocalDate free = p.getOpenTo() != null ? p.getOpenTo() : p.getOpenFrom();
        LocalDate arrival = free == null ? null : free.plusDays((long) Math.ceil(leg.days()));
        String description = describe(leg);

        if (c.getLaycanTo() == null || arrival == null) {
            String why = c.getLaycanTo() == null
                    ? description + " - cargo gives no cancelling date"
                    : description + " - her position gives no date";
            return new Timing(new Check("timing", "Position", Verdict.PASS, W_TIMING, why),
                    leg, arrival);
        }

        if (arrival.isAfter(c.getLaycanTo())) {
            return new Timing(new Check("timing", "Position", Verdict.FAIL, W_TIMING,
                    "%s - arrives %s, cancelling %s".formatted(description, arrival, c.getLaycanTo())),
                    leg, arrival);
        }
        return new Timing(new Check("timing", "Position", Verdict.PASS, W_TIMING,
                "%s - arrives %s, laycan to %s".formatted(description, arrival, c.getLaycanTo())),
                leg, arrival);
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
