package com.chartering.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.stream.Stream;

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

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", " \n "})
    void aBlankNameGivesAnEmptyKey(String blank) {
        assertThat(CompanyNames.similarityKey(blank)).isEqualTo("");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORDIC STAR", "Nordic Star", "nordic star", "nOrDiC sTaR"})
    void caseIsFoldedAway(String name) {
        assertThat(CompanyNames.similarityKey(name)).isEqualTo("nordicstar");
    }

    @ParameterizedTest(name = "{0} and {1} share a key")
    @CsvSource(delimiter = '|', value = {
            "Examplar Ltd.|EXAMPLAR",
            "Kestrel Shipping|KESTREL",
            "Kayaexample Denizcilik Ltd. Şti.|Kayaexample Denizcilik",
            "ŞAHİNEXAMPLE|Sahinexample",
    })
    void namesWrittenTwoWaysForOneFirmGiveOneKey(String first, String second) {
        assertThat(CompanyNames.similarityKey(first)).isEqualTo(CompanyNames.similarityKey(second));
    }

    static Stream<String> legalSuffixes() {
        return CompanyNames.LEGAL_SUFFIXES.stream().sorted();
    }

    /**
     * Every entry, not a sample: an entry the word split can never produce — upper case,
     * punctuation inside it, a space — would sit on the list matching nothing, and only a test
     * that tries each one finds it. Written as a name ends in the wild, capitalised and dotted.
     */
    @ParameterizedTest
    @MethodSource("legalSuffixes")
    void everyLegalSuffixOnTheListIsDropped(String suffix) {
        assertThat(CompanyNames.similarityKey("Examplar " + suffix)).isEqualTo("examplar");
        assertThat(CompanyNames.similarityKey("Examplar " + suffix.toUpperCase(Locale.ROOT) +".")).isEqualTo("examplar");
    }

    @Test
    void aLegalFormIsDroppedWhereverItStandsInTheName() {
        assertThat(CompanyNames.similarityKey("Examplar Shipping Partners")).isEqualTo("examplarpartners");
        assertThat(CompanyNames.similarityKey("AS Examplar")).isEqualTo("examplar");
    }

    @Test
    void aWordThatOnlyContainsALegalFormIsKept() {
        // "co", "ag" and "sa" are on the list; Coastal, Agrotrade and Saltmarsh are not them.
        assertThat(CompanyNames.similarityKey("Coastal Agrotrade Saltmarsh"))
                .isEqualTo("coastalagrotradesaltmarsh");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ltd", "Shipping Ltd.", "Holding Co", "A/S", "GmbH & Co. KG"})
    void aNameThatIsOnlyALegalFormGivesAnEmptyKey(String name) {
        assertThat(CompanyNames.similarityKey(name)).isEqualTo("");
    }

    @Test
    void theScandinavianPartnershipFormsAreStrippedWholeWithNoStrayLetters() {
        assertThat(CompanyNames.similarityKey("Nordic Star A/S")).isEqualTo("nordicstar");
        assertThat(CompanyNames.similarityKey("Nordic Star K/S")).isEqualTo("nordicstar");
    }

    @Test
    void aSlashSeparatesALegalFormFromTheWordBeforeIt() {
        // Were the slash deleted rather than turned into a space, "examplarltd" would be one
        // word and no suffix would match it.
        assertThat(CompanyNames.similarityKey("Examplar/Ltd")).isEqualTo("examplar");
    }

    @Test
    void theTurkishLegalFormIsDroppedAsItIsWrittenWithItsCedilla() {
        assertThat(CompanyNames.similarityKey("Kayaexample Denizcilik Ltd. Şti.")).isEqualTo("kayaexampledenizcilik");
        assertThat(CompanyNames.similarityKey("Kayaexample Denizcilik Ltd.Şti.")).isEqualTo("kayaexampledenizcilik");
        assertThat(CompanyNames.similarityKey("Kayaexample Denizcilik LTDŞTİ")).isEqualTo("kayaexampledenizcilik");
    }

    @Test
    void accentsAreFoldedOffTheirLetters() {
        assertThat(CompanyNames.similarityKey("ŞAHİNEXAMPLE")).isEqualTo("sahinexample");
        assertThat(CompanyNames.similarityKey("Çelikexample Öztürk Gemicilik")).isEqualTo("celikexampleozturkgemicilik");
        assertThat(CompanyNames.similarityKey("Yağmurexample")).isEqualTo("yagmurexample");
    }

    @Test
    void aDottedCapitalIAndALowerCaseDotlessIBothReachAPlainI() {
        // İ decomposes to i and a combining dot, and the dot is folded off; ı has nothing to
        // decompose into and is mapped by hand.
        assertThat(CompanyNames.similarityKey("DENİZCİLİK")).isEqualTo("denizcilik");
        assertThat(CompanyNames.similarityKey("nakliyatı")).isEqualTo("nakliyati");
    }

    @Test
    void aLetterWithNoPlainBaseIsDroppedWithThePunctuation() {
        // ø is a letter of its own, not an o with a mark on it, so it has nothing to fold to.
        assertThat(CompanyNames.similarityKey("Ørnexample")).isEqualTo("rnexample");
    }

    @Test
    void digitsAreKept() {
        assertThat(CompanyNames.similarityKey("Examplar 2000 Ltd")).isEqualTo("examplar2000");
    }
}
