package com.chartering.service.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name-resemblance half of the matcher, which is pure. Every name here is invented; the
 * two "generic" ones only reproduce the shape of a real false positive the live check caught —
 * a firm whose name is a legal form wrapped round trade words.
 */
class CompanyMatcherTest {

    @Test
    void tradeWordsAndLegalFormsDoNotMakeTwoFirmsAlike() {
        assertThat(CompanyMatcher.resemblance("North Sea Example Chartering A/S",
                "AG Shipping & Chartering Co. Ltd")).isNull();
        assertThat(CompanyMatcher.resemblance("North Sea Example Chartering A/S",
                "Shipping & Chartering GmbH & Co KG")).isNull();
    }

    @Test
    void theSameFirmWrittenTwoWaysStillResembles() {
        assertThat(CompanyMatcher.resemblance("Example Chartering A/S", "EXAMPLE CHARTERING"))
                .isEqualTo("similar name");
        assertThat(CompanyMatcher.resemblance("Nordexample Maritime Ltd", "NORDEXAMPLE"))
                .isEqualTo("similar name");
    }

    @Test
    void oneNameInsideAnotherCountsOnlyWithFiveLettersOfItsOwn() {
        assertThat(CompanyMatcher.resemblance("Examplar Shipping", "Examplar Bulk Carriers Group"))
                .isNotNull();
        // "Sea Line" is nothing but trade words: nothing distinctive left to contain.
        assertThat(CompanyMatcher.resemblance("Sea Line Ltd", "Sea Line Examplar Shipping")).isNull();
    }

    @Test
    void phonesCompareOnTheirLastNineDigits() {
        assertThat(CompanyMatcher.tail("+45-00000001")).isEqualTo(CompanyMatcher.tail("0045 0000 0001"));
        assertThat(CompanyMatcher.tail("12345")).isNull();
    }
}
