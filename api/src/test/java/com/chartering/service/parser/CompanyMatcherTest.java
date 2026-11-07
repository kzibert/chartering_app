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
    void sameFirmReadsTradeWordsPunctuationAndOfficeAsOneFirm() {
        assertThat(CompanyMatcher.sameFirm("Harlow&Bulk", "HARLOWBULK")).isTrue();
        assertThat(CompanyMatcher.sameFirm("Damarax Example Chartering", "Damarax Example")).isTrue();
        assertThat(CompanyMatcher.sameFirm("Corvane Lines", "Corvane Chartering & Operations")).isTrue();
        assertThat(CompanyMatcher.sameFirm("Seafright Example", "Seafright Example Chartring")).isTrue();
        assertThat(CompanyMatcher.sameFirm("Bermuda Example Shipping", "Bermuda Example -Istanbul")).isTrue();
        assertThat(CompanyMatcher.sameFirm("Corvane Example Ltd", "CORVANE EXAMPLE")).isTrue();
    }

    @Test
    void aDifferentNameIsARenameAndAShortNameContainsNothing() {
        assertThat(CompanyMatcher.sameFirm("Oceanic Example Ltd", "Blue Example Shipping")).isFalse();
        // "Alba" has four letters: too short to be evidence that "Albatross" is the same firm.
        assertThat(CompanyMatcher.sameFirm("Alba", "Albatross Example")).isFalse();
        // Nothing but trade words on either side: compared on legal forms alone, and these differ.
        assertThat(CompanyMatcher.sameFirm("Shipping Chartering", "Maritime Operations")).isFalse();
    }

    @Test
    void phonesCompareOnTheirLastNineDigits() {
        assertThat(CompanyMatcher.tail("+45-00000001")).isEqualTo(CompanyMatcher.tail("0045 0000 0001"));
        assertThat(CompanyMatcher.tail("12345")).isNull();
    }
}
