package com.chartering.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turning "25,000 MT +/- 10%" into a range a query can compare against.
 *
 * <p>A tolerance arrives written any of a dozen ways, and only some of them are arithmetic:
 *
 * <pre>
 *   +/- 10%      10 pct      -5/+10%     min/max      MOLOO      MOLCO
 *   10 pct in owner's option              about       abt 5%
 * </pre>
 *
 * <p>The first group can be read. The second group cannot — MOLOO is "more or less owner's
 * option", which is a percentage the charter party settles and this email does not state.
 *
 * <p><b>An unreadable tolerance produces no range, and that is the whole point.</b> Guessing
 * five percent because five is common would put a number nobody stated into a field Match
 * treats as fact, and the ship it wrongly excluded would never be seen to have been
 * excluded. Empty here means Match falls back to the nominal quantity and says on screen
 * that the tolerance was not read.
 */
public final class QuantityTolerance {

    private QuantityTolerance() {
    }

    /**
     * A symmetric percentage: "+/- 10%", "10 pct", "abt 7.5 %", "± 10".
     *
     * <p>Anchored on the percent marker rather than on the leading sign, because the sign is
     * the part that is written five ways and the marker is the part that means percentage.
     */
    private static final Pattern SYMMETRIC = Pattern.compile(
            "([0-9]+(?:[.,][0-9]+)?)\\s*(?:%|pct|percent)", Pattern.CASE_INSENSITIVE);

    /** An asymmetric one: "-5/+10%", "+10/-5 pct". */
    private static final Pattern ASYMMETRIC = Pattern.compile(
            "([+-])\\s*([0-9]+(?:[.,][0-9]+)?)\\s*/\\s*([+-])\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(?:%|pct|percent)",
            Pattern.CASE_INSENSITIVE);

    /** The low and high ends a quantity may actually be loaded at. */
    public record Range(BigDecimal min, BigDecimal max) {
    }

    /**
     * The range a quantity and a tolerance describe.
     *
     * @return empty when there is no quantity, or when the tolerance says something that is
     *         not a percentage. A blank tolerance is not unreadable — it means the quantity
     *         is exact, and gives a range of that quantity to itself.
     */
    public static Optional<Range> rangeOf(BigDecimal quantity, String tolerance) {
        if (quantity == null || quantity.signum() <= 0) return Optional.empty();
        if (tolerance == null || tolerance.isBlank()) return Optional.of(new Range(quantity, quantity));

        Matcher asym = ASYMMETRIC.matcher(tolerance);
        if (asym.find()) {
            BigDecimal a = apply(quantity, asym.group(1), asym.group(2));
            BigDecimal b = apply(quantity, asym.group(3), asym.group(4));
            return Optional.of(a.compareTo(b) <= 0 ? new Range(a, b) : new Range(b, a));
        }

        Matcher sym = SYMMETRIC.matcher(tolerance);
        if (sym.find()) {
            BigDecimal pct = number(sym.group(1));
            return Optional.of(new Range(
                    scale(quantity.subtract(quantity.multiply(pct).divide(HUNDRED, 6, RoundingMode.HALF_UP))),
                    scale(quantity.add(quantity.multiply(pct).divide(HUNDRED, 6, RoundingMode.HALF_UP)))));
        }

        return Optional.empty();
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static BigDecimal apply(BigDecimal quantity, String sign, String digits) {
        BigDecimal delta = quantity.multiply(number(digits)).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        return scale("-".equals(sign) ? quantity.subtract(delta) : quantity.add(delta));
    }

    /** European decimal commas are as common as points in this mail. */
    private static BigDecimal number(String digits) {
        return new BigDecimal(digits.replace(',', '.'));
    }

    /**
     * Whole tonnes. Nobody quotes a cargo to three decimal places, and a stored 22499.999
     * would print as one in every list that showed it.
     */
    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }
}
