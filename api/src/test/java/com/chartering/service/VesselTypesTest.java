package com.chartering.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wordings are the one-off types found in the fleet on 2026-09-14 (type descriptions, not
 * anybody's details), each with the category the desk agreed it names.
 */
class VesselTypesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "GENERAL CARGO|SEA TYPE",
            "Gen Cargo|SEA TYPE",
            "General Cargo Ship|SEA TYPE",
            "GENERAL CARGO VESSEL|SEA TYPE",
            "general cargo|SEA TYPE",
            "SID/GC|SEA TYPE",
            "SID, General Cargo|SEA TYPE",
            "SINGLE-DECKER GENERAL DRY CARGO SHIP|SEA TYPE",
            "GC/CONT|SEA TYPE",
            "BC GRD|SEA TYPE",
            "SID, BOX|SEA TYPE BOX SHAPE",
            "SID BOX|SEA TYPE BOX SHAPE",
            "SID/BOXLIKE|SEA TYPE BOX SHAPE",
            "BOXLIKE|SEA TYPE BOX SHAPE",
            "SID / Box-Shaped|SEA TYPE BOX SHAPE",
            "'single decker,gearless,box'|SEA TYPE BOX SHAPE",
            "GENERAL CARGO / BOX / DOUBLE SKINNED|SEA TYPE BOX SHAPE",
            "'SID, Double Skin, Semi-Box'|SEA TYPE BOX SHAPE",
            "GC / (Box / Open Hatch)|SEA TYPE BOX SHAPE",
            "nearly boxshaped (62 m w/o narrowing)|SEA TYPE BOX SHAPE",
            "SEA-RIVER TYPE WITHOUT HATCH COMINGS|SEA+RIVER TYPE",
            "'Sormovsky Type, Ice-Classed'|SEA+RIVER TYPE",
            "TWEENDECK –OHBS-GENERAL CARGO|TWEENDECKER",
    })
    void mapsTheFleetsOneOffWordingsToTheAgreedCategory(String wording, String category) {
        assertThat(VesselTypes.canonical(wording)).isEqualTo(category);
    }

    @Test
    void aWordingThatDescribesGearRatherThanAHullNamesNoCategory() {
        assertThat(VesselTypes.canonical("GRD 2 CR 120 MT COMBI 240 MT")).isNull();
        assertThat(VesselTypes.canonical("")).isNull();
        assertThat(VesselTypes.canonical(null)).isNull();
    }

    @Test
    void theCategoriesThemselvesMapToThemselvesWhateverTheCase() {
        for (String c : VesselTypes.CANONICAL) {
            assertThat(VesselTypes.canonical(c.toLowerCase())).isEqualTo(c);
        }
        assertThat(VesselTypes.isCanonical("SEA TYPE")).isTrue();
        assertThat(VesselTypes.isCanonical("sea type")).isFalse();
    }

    @Test
    void specificKindsWinOverTheGeneralWordsAroundThem() {
        assertThat(VesselTypes.canonical("PRODUCT TANKER / GENERAL")).isEqualTo("TANKER");
        assertThat(VesselTypes.canonical("RIVER TYPE ONLY")).isEqualTo("RIVER TYPE ONLY");
        assertThat(VesselTypes.canonical("river-only barge")).isEqualTo("BARGE");
    }
}
