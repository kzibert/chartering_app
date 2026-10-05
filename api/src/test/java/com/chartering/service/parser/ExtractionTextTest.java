package com.chartering.service.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Free text read out of a circular, fitted to the column it lands in. A broker's 231-character
 * terms line against a 200 column used to fail the update and with it the whole merge.
 */
class ExtractionTextTest {

    @Test
    void textThatFitsIsOnlyStripped() {
        assertThat(Extraction.text("  EU acc; abt 47'  ", 200)).isEqualTo("EU acc; abt 47'");
    }

    @Test
    void textTooLongIsCutToTheColumnAndSaysSo() {
        String fitted = Extraction.text("x".repeat(231), 200);
        assertThat(fitted).hasSize(200).endsWith("\u2026");
    }

    @Test
    void blankIsStillNothing() {
        assertThat(Extraction.text("   ", 200)).isNull();
        assertThat(Extraction.text(null, 200)).isNull();
    }
}
