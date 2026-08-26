package com.chartering.service;

import com.chartering.model.Cargo;
import com.chartering.model.Vessel;
import com.chartering.model.VesselPosition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * the berth. That is a real answer and the pair should not be proposed. Missing data is not
 * an answer.
 *
 * <p><b>The score is what fraction of the applicable weight passed.</b> Criteria the cargo
 * says nothing about are not applicable and drop out of both halves of the fraction: a cargo
 * with no draft limit does not reward a ship for being shallow. Criteria the cargo does
 * state but the vessel cannot answer stay in the denominator, which is what makes a
 * well-documented hull outrank an unknown one carrying the same guesses.
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
     * @param detail what to show a broker — always the actual figures, never "failed size
     *               check". The point of the screen is that the reason can be argued with.
     */
    public record Check(String code, String label, Verdict verdict, int weight, String detail) {
    }

    /**
     * @param ruledOut       any check FAILed
     * @param ballastDays    the leg from her open area to the load area, when both are known
     *                       and something on file connects them
     * @param earliestArrival when she could be at the load port, on those ballast days
     */
    public record Result(int score,
                         List<Check> checks,
                         boolean ruledOut,
                         Double ballastDays,
                         LocalDate earliestArrival) {

        public long unknownCount() {
            return checks.stream().filter(c -> c.verdict() == Verdict.UNKNOWN).count();
        }

        public List<Check> failures() {
            return checks.stream().filter(c -> c.verdict() == Verdict.FAIL).toList();
        }
    }

    // The weights. Size and timing are what a broker asks first and they are worth more than
    // everything else together; the fittings are real knockouts but a small part of "how good
    // is this fit" once they have passed.
    private static final int W_SIZE = 30;
    private static final int W_TIMING = 30;
    private static final int W_CUBIC = 10;
    private static final int W_DRAFT = 10;
    private static final int W_GEAR = 10;
    private static final int W_FITTING = 5;
    private static final int W_AGE = 5;

    /** Cubic feet in a cubic metre, for comparing a stowage factor against a grain capacity. */
    private static final BigDecimal CBFT_PER_M3 = new BigDecimal("35.3147");

    public static Result score(Cargo cargo, VesselPosition position, TradeAreaGraph areas,
                               LocalDate today) {
        Vessel v = position.getVessel();
        List<Check> checks = new ArrayList<>();

        BigDecimal capacity = cargoCapacity(v);
        checks.add(sizeCheck(cargo, v, capacity));
        checks.add(cubicCheck(cargo, v));
        checks.add(draftCheck(cargo, v));
        checks.add(gearCheck(cargo, v));
        checks.add(fittingCheck("grain_fitted", "Grain fitted",
                cargo.getRequiresGrainFitted(), v.getGrainFitted()));
        checks.add(fittingCheck("imo_fitted", "IMO fitted",
                cargo.getRequiresImoFitted(), v.getImoFitted()));
        checks.add(ageCheck(cargo, v, today));

        Timing timing = timing(cargo, position, areas);
        checks.add(timing.check());

        checks.removeIf(java.util.Objects::isNull);

        int applicable = checks.stream().mapToInt(Check::weight).sum();
        int passed = checks.stream()
                .filter(c -> c.verdict() == Verdict.PASS)
                .mapToInt(Check::weight).sum();
        // Nothing applicable means the cargo states no requirement this scorer can test. That
        // is not a perfect match and it is not a bad one; 0 sorts it below anything actually
        // verified, and the empty check list on screen says why.
        int score = applicable == 0 ? 0 : Math.round(100f * passed / applicable);
        boolean ruledOut = checks.stream().anyMatch(c -> c.verdict() == Verdict.FAIL);

        return new Result(score, List.copyOf(checks), ruledOut,
                timing.ballastDays(), timing.arrival());
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

    private record Timing(Check check, Double ballastDays, LocalDate arrival) {
    }

    /**
     * Can she be there in time?
     *
     * <p>This is where the trade areas earn their place. The load point resolves to an area
     * (the port's own if a port was named, else the one entered); so does her position; the
     * distance table says how many days lie between them; and her open date plus those days
     * is the earliest she can present. If that is after the cancelling date, she cannot make
     * it — which is a fact, and rules the pair out.
     *
     * <p>Four different absences are four different answers here, and collapsing them would
     * be the easiest way to make this screen untrustworthy:
     *
     * <ul>
     *   <li>The cargo names no load area — nothing to test, the check does not apply.
     *   <li>Her position names no area — UNKNOWN. Somebody wrote a position we could not
     *       resolve, and that is worth seeing rather than guessing past.
     *   <li>Both known but nothing on file connects them — FAIL. The distance table holds the
     *       pairs this desk would consider, so a missing pair means too far to consider, and
     *       saying "unknown" would invite offering a Caspian ship for a Med cargo.
     *   <li>Reachable, but the cargo gives no laycan — PASS, with the ballast leg named. Half
     *       the enquiries in this mailbox say "laycan: please advise", and refusing to match
     *       them would refuse to match the ones most in need of tonnage.
     * </ul>
     */
    private static Timing timing(Cargo c, VesselPosition p, TradeAreaGraph areas) {
        Long cargoArea = areaOf(c.getLoadPort() != null ? c.getLoadPort().getTradeArea() : null,
                c.getLoadArea());
        if (cargoArea == null) return new Timing(null, null, null);

        Long openArea = areaOf(p.getOpenPort() != null ? p.getOpenPort().getTradeArea() : null,
                p.getOpenArea());
        if (openArea == null) {
            return new Timing(new Check("timing", "Position", Verdict.UNKNOWN, W_TIMING,
                    "Her position names no area we could resolve"), null, null);
        }

        OptionalDouble days = areas.ballastDays(openArea, cargoArea);
        String from = nameOf(areas, openArea);
        String to = nameOf(areas, cargoArea);
        if (days.isEmpty()) {
            return new Timing(new Check("timing", "Position", Verdict.FAIL, W_TIMING,
                    "Nothing on file connects %s and %s - too far to consider".formatted(from, to)),
                    null, null);
        }

        double ballast = days.getAsDouble();
        // Her latest free day, not her earliest: a ship open 1/3 September is not sailing on
        // the 1st, and using the optimistic end would put ships on lists they cannot make.
        LocalDate free = p.getOpenTo() != null ? p.getOpenTo() : p.getOpenFrom();
        LocalDate arrival = free == null ? null : free.plusDays((long) Math.ceil(ballast));

        String leg = ballast == 0
                ? "Already in %s".formatted(to)
                : "%s to %s, about %s days".formatted(from, to, trim(ballast));

        if (c.getLaycanTo() == null || arrival == null) {
            String why = c.getLaycanTo() == null
                    ? leg + " - cargo gives no cancelling date"
                    : leg + " - her position gives no date";
            return new Timing(new Check("timing", "Position", Verdict.PASS, W_TIMING, why),
                    ballast, arrival);
        }

        if (arrival.isAfter(c.getLaycanTo())) {
            return new Timing(new Check("timing", "Position", Verdict.FAIL, W_TIMING,
                    "%s - arrives %s, cancelling %s".formatted(leg, arrival, c.getLaycanTo())),
                    ballast, arrival);
        }
        return new Timing(new Check("timing", "Position", Verdict.PASS, W_TIMING,
                "%s - arrives %s, laycan to %s".formatted(leg, arrival, c.getLaycanTo())),
                ballast, arrival);
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

    private static String trim(double days) {
        return days == Math.rint(days) ? String.valueOf((long) days) : String.valueOf(days);
    }
}
