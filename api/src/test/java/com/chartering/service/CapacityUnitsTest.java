package com.chartering.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The figures are shaped like this fleet's — a 28,000-tonner holding about 37,000 m³ — and
 * belong to no particular ship.
 */
class CapacityUnitsTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void aFigureThatFitsTheHullInCubicMetresIsCubicMetres() {
        assertThat(CapacityUnits.bySize(d("37000"), d("28000"), null)).isEqualTo(CapacityUnits.Unit.CBM);
    }

    @Test
    void aFigureThirtyFiveTimesTooLargeForTheHullIsCubicFeet() {
        assertThat(CapacityUnits.bySize(d("1306000"), d("28000"), null)).isEqualTo(CapacityUnits.Unit.CBFT);
    }

    @Test
    void cargoDeadweightStandsInWhenThereIsNoDeadweight() {
        assertThat(CapacityUnits.bySize(d("160000"), null, d("3600"))).isEqualTo(CapacityUnits.Unit.CBFT);
        assertThat(CapacityUnits.bySize(d("4600"), d("0"), d("3600"))).isEqualTo(CapacityUnits.Unit.CBM);
    }

    @Test
    void theSizeOverridesALabelItContradicts() {
        // "GRAIN 1,306,000 CBM" on a 28,000-tonner: the label is the mistake, not the figure.
        assertThat(CapacityUnits.resolve("cbm", d("1306000"), d("28000"), null)).isEqualTo(CapacityUnits.Unit.CBFT);
    }

    @Test
    void withNoSizeToJudgeByTheStatedUnitIsTakenAndNoUnitIsNoAnswer() {
        assertThat(CapacityUnits.resolve("CBFT", d("1306000"), null, null)).isEqualTo(CapacityUnits.Unit.CBFT);
        assertThat(CapacityUnits.resolve("", d("1306000"), null, null)).isNull();
    }

    @Test
    void aFigureAbsurdInBothUnitsFallsBackToTheLabel() {
        // Four m³ per tonne is too much for a hold and far too little as cubic feet.
        assertThat(CapacityUnits.bySize(d("112000"), d("28000"), null)).isNull();
        assertThat(CapacityUnits.resolve("cbm", d("112000"), d("28000"), null)).isEqualTo(CapacityUnits.Unit.CBM);
    }

    @Test
    void convertsCubicFeetToCubicMetres() {
        // 36,981.6 m³, rounded to whole cubic metres.
        assertThat(CapacityUnits.toCubicMetres(d("1306000"), CapacityUnits.Unit.CBFT)).isEqualByComparingTo("36982");
        assertThat(CapacityUnits.toCubicMetres(d("37000"), CapacityUnits.Unit.CBM)).isEqualByComparingTo("37000");
        assertThat(CapacityUnits.toCubicMetres(d("37000"), null)).isNull();
    }

    @Test
    void labelsAreReadLoosely() {
        assertThat(CapacityUnits.stated("Cu.Ft")).isEqualTo(CapacityUnits.Unit.CBFT);
        assertThat(CapacityUnits.stated("m³")).isNull();
        assertThat(CapacityUnits.stated("M3")).isEqualTo(CapacityUnits.Unit.CBM);
        assertThat(CapacityUnits.stated("bbl")).isNull();
    }
}
