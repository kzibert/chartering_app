package com.chartering.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a tolerance, and — more importantly — refusing to read one that is not arithmetic.
 * The refusals are the half worth testing: a wrong range silently excludes the ship that
 * would have worked, and nothing on screen would ever say so.
 */
class QuantityToleranceTest {

    private static final BigDecimal TWENTY_FIVE_K = new BigDecimal("25000");

    @Test
    void readsTheSymmetricPercentageEverybodyWrites() {
        assertRange("+/- 10%", "22500", "27500");
        assertRange("10%", "22500", "27500");
        assertRange("10 pct", "22500", "27500");
        assertRange("abt 5 percent", "23750", "26250");
    }

    @Test
    void readsADecimalPercentageWrittenWithEitherSeparator() {
        assertRange("7.5%", "23125", "26875");
        assertRange("7,5%", "23125", "26875");
    }

    @Test
    void readsAnAsymmetricPercentageInEitherOrder() {
        assertRange("-5/+10%", "23750", "27500");
        // Written high end first; the range still comes back low end first.
        assertRange("+10/-5 pct", "23750", "27500");
    }

    @Test
    void treatsNoToleranceAtAllAsAnExactQuantity() {
        assertRange(null, "25000", "25000");
        assertRange("   ", "25000", "25000");
    }

    @Test
    void refusesToInventARangeForATradeTermThatStatesNoPercentage() {
        // MOLOO is "more or less owner's option" - a percentage the charter party settles
        // and this email does not state. Guessing five because five is common would put a
        // number nobody wrote into a field Match reads as fact.
        assertThat(QuantityTolerance.rangeOf(TWENTY_FIVE_K, "MOLOO")).isEmpty();
        assertThat(QuantityTolerance.rangeOf(TWENTY_FIVE_K, "MOLCO")).isEmpty();
        assertThat(QuantityTolerance.rangeOf(TWENTY_FIVE_K, "min/max")).isEmpty();
        assertThat(QuantityTolerance.rangeOf(TWENTY_FIVE_K, "in owner's option")).isEmpty();
    }

    @Test
    void hasNothingToSayWithoutAQuantity() {
        assertThat(QuantityTolerance.rangeOf(null, "+/- 10%")).isEmpty();
        assertThat(QuantityTolerance.rangeOf(BigDecimal.ZERO, "+/- 10%")).isEmpty();
    }

    @Test
    void roundsToWholeTonnes() {
        // 3,333 +/- 10% is 2,999.7 exactly, and no list prints that.
        assertThat(QuantityTolerance.rangeOf(new BigDecimal("3333"), "10%"))
                .get()
                .extracting(QuantityTolerance.Range::min, QuantityTolerance.Range::max)
                .containsExactly(new BigDecimal("3000"), new BigDecimal("3666"));
    }

    private void assertRange(String tolerance, String min, String max) {
        assertThat(QuantityTolerance.rangeOf(TWENTY_FIVE_K, tolerance))
                .as("tolerance %s", tolerance)
                .get()
                .extracting(QuantityTolerance.Range::min, QuantityTolerance.Range::max)
                .containsExactly(new BigDecimal(min), new BigDecimal(max));
    }
}
