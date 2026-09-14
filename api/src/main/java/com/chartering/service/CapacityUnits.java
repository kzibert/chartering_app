package com.chartering.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Whether a hold capacity is in cubic metres or cubic feet — read off the ship's size when
 * the words do not say, or say wrong.
 *
 * <p><b>The two units are thirty-five apart, and that is what makes the question answerable.</b>
 * A cargo hold carries roughly 1.0 to 1.7 m³ per tonne of deadweight: this fleet, measured, has
 * 3,072 of its 3,221 grain figures in that band and all but a handful between 0.7 and 2.6. In
 * cubic feet the same holds read 25 to 92 per tonne. Nothing a real ship holds falls between
 * the two bands, so a figure set against her deadweight lands in one of them or is nonsense —
 * and a circular that writes "GRAIN 1,650,000" for a 38,000-tonner has told us its unit as
 * plainly as if it had typed "cbft".
 *
 * <p><b>The size outranks the word.</b> Where a figure is plausible in exactly one unit, that
 * unit is the answer whatever the text said beside it: brokers write "cbm" under a column of
 * cubic feet often enough that trusting the label would put the error in the record. Where the
 * size cannot decide — no deadweight given, or a figure absurd either way — the stated unit is
 * taken, and a figure with neither is not guessed at all. A guess there is a handysize recorded
 * as holding 144,000 m³, and nothing downstream would ever question it.
 *
 * <p>Deadweight first, cargo deadweight when that is all there is: DWCC runs a few percent
 * under DWT, which moves the ratio far less than the width of either band.
 */
public final class CapacityUnits {

    /** Cubic feet in a cubic metre. Exact by definition of the foot; rounded where used. */
    public static final BigDecimal CBFT_PER_CBM = new BigDecimal("35.3146667");

    /** The band a hold's capacity per tonne of deadweight falls in, in cubic metres. */
    static final double MIN_M3_PER_TONNE = 0.7;
    static final double MAX_M3_PER_TONNE = 2.6;

    private static final MathContext MC = new MathContext(9, RoundingMode.HALF_UP);

    public enum Unit { CBM, CBFT }

    private CapacityUnits() {
    }

    /** The unit a text named, or null for none or one nobody recognises. */
    public static Unit stated(String unit) {
        if (unit == null || unit.isBlank()) return null;
        return switch (unit.replaceAll("[^A-Za-z0-9]", "").toUpperCase()) {
            case "CBM", "M3", "CBMS", "CUM", "CUBICMETRES", "CUBICMETERS" -> Unit.CBM;
            case "CBFT", "CFT", "CBF", "FT3", "CUFT", "CUBICFEET" -> Unit.CBFT;
            default -> null;
        };
    }

    /**
     * The unit a figure has to be in for a hull this size to hold it, or null when the size
     * cannot say — no size known, or a figure implausible in both units.
     */
    public static Unit bySize(BigDecimal value, BigDecimal dwt, BigDecimal dwcc) {
        BigDecimal size = positive(dwt) != null ? dwt : positive(dwcc);
        if (positive(value) == null || size == null) return null;
        double perTonne = value.divide(size, MC).doubleValue();
        if (plausible(perTonne)) return Unit.CBM;
        if (plausible(perTonne / CBFT_PER_CBM.doubleValue())) return Unit.CBFT;
        return null;
    }

    /** The unit to read a figure in: the size's answer where it has one, else the stated unit. */
    public static Unit resolve(String statedUnit, BigDecimal value, BigDecimal dwt, BigDecimal dwcc) {
        Unit fromSize = bySize(value, dwt, dwcc);
        return fromSize != null ? fromSize : stated(statedUnit);
    }

    /** A figure in cubic metres, or null when there is no figure or no unit to read it in. */
    public static BigDecimal toCubicMetres(BigDecimal value, Unit unit) {
        if (value == null || unit == null) return null;
        return unit == Unit.CBM ? value : value.divide(CBFT_PER_CBM, MC);
    }

    private static boolean plausible(double m3PerTonne) {
        return m3PerTonne >= MIN_M3_PER_TONNE && m3PerTonne <= MAX_M3_PER_TONNE;
    }

    private static BigDecimal positive(BigDecimal d) {
        return d == null || d.signum() <= 0 ? null : d;
    }
}
