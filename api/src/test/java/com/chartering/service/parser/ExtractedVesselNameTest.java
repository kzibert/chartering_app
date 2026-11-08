package com.chartering.service.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model reads "MV Queen Vivian" and the ship is called QUEEN VIVIAN. Left on, the prefix
 * missed the exact-name match and raised rename rows against hulls matched by IMO.
 */
class ExtractedVesselNameTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "MV Queen Vivian|Queen Vivian",
            "M/V DOLPHIN 505|DOLPHIN 505",
            "mv tulips|tulips",
            "M.V. ANNA|ANNA",
            "MV. ANNA|ANNA",
            "M V ANNA|ANNA",
            "MV: ANNA|ANNA",
            "MT ELBRUS|ELBRUS",
            "M/T ELBRUS|ELBRUS",
            "MS HELGA|HELGA",
            "SS ATLANTIC|ATLANTIC",
            "  MV  RONGHAI 1 |RONGHAI 1"})
    void prefixIsNotPartOfTheName(String raw, String expected) {
        assertThat(Extraction.vesselName(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "MVK ATLANTIC",
            "MTS VALOUR",
            "TUG ATLAS",
            "MASTER MV",
            "MSC ANNA",
            "MV"})
    void aNameThatOnlyLooksLikeOneIsLeftAlone(String raw) {
        assertThat(Extraction.vesselName(raw)).isEqualTo(raw);
    }

    @Test
    void absentStaysAbsent() {
        assertThat(Extraction.vesselName(null)).isNull();
    }

    @Test
    void theModelsAnswerIsCleanedAsItIsRead() throws Exception {
        Extraction extraction = new ObjectMapper().readValue(
                "{\"type\":\"vessel_opening\",\"vessels\":[{\"name\":\"M/V RONGHAI 1\",\"imo\":\"\"}]}",
                Extraction.class);

        assertThat(extraction.vesselsOrEmpty()).singleElement()
                .extracting(Extraction.ExtractedVessel::name).isEqualTo("RONGHAI 1");
    }
}
