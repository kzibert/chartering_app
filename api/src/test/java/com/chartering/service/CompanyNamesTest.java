package com.chartering.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The similarity key is only a hint for "might these be one firm", so these tests pin what it
 * collapses and what it deliberately leaves apart. Company names here are invented; the
 * non-ASCII cases are the dotted and cedilla capitals a Turkish owner's name arrives with.
 */
class CompanyNamesTest {

    @Test
    void aMissingNameGivesAnEmptyKey() {
        assertThat(CompanyNames.similarityKey(null)).isEqualTo("");
    }

    @ParameterizedTest(name = "{0} and {1} share a key")
    @CsvSource(delimiter = '|', value = {
            "Fednav Ltd.|FEDNAV",
            "Soylu Shipping|SOYLU",
            "Nordic Bulk A/S|Nordic Bulk K/S",
    })
    void namesWrittenTwoWaysForOneFirmGiveOneKey(String first, String second) {
        assertThat(CompanyNames.similarityKey(first)).isEqualTo(CompanyNames.similarityKey(second));
    }

    @Test
    void aLegalFormSuffixAndItsPunctuationAreDroppedLeavingTheFirmsName() {
        assertThat(CompanyNames.similarityKey("Fednav Ltd.")).isEqualTo("fednav");
        assertThat(CompanyNames.similarityKey("Soylu Shipping")).isEqualTo("soylu");
    }

    @Test
    void theScandinavianPartnershipFormsAreStrippedWholeWithNoStrayLetters() {
        assertThat(CompanyNames.similarityKey("Nordic Bulk A/S")).isEqualTo("nordicbulk");
        assertThat(CompanyNames.similarityKey("Nordic Bulk K/S")).isEqualTo("nordicbulk");
    }

    @Test
    void aSlashThatIsNotALegalFormStillSplitsWords() {
        assertThat(CompanyNames.similarityKey("Gen/Cargo")).isEqualTo("gencargo");
    }

    @Test
    void aLetterOutsideAToZIsDroppedSoTheTwoSpellingsMeetOnlyPartway() {
        // Ş lowercases to ş, outside a-z, and goes with the punctuation; the ASCII spelling keeps
        // its s, so the keys differ by exactly that letter.
        assertThat(CompanyNames.similarityKey("ŞAHİN")).isEqualTo("ahin");
        assertThat(CompanyNames.similarityKey("SAHIN")).isEqualTo("sahin");
    }

    @Test
    void aDottedCapitalIKeepsItsLetterSoDenizcilikMeetsInFull() {
        // Locale.ROOT lowercases İ to i plus a combining dot (U+0307). Only the dot is outside
        // a-z, so only the dot is dropped and the i survives: unlike what the Javadoc's example
        // says, the two spellings of this word collide outright. Pinned as it behaves.
        assertThat(CompanyNames.similarityKey("DENİZCİLİK")).isEqualTo("denizcilik");
        assertThat(CompanyNames.similarityKey("DENIZCILIK")).isEqualTo("denizcilik");
    }
}
