package com.chartering.service.lookup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning the name a broker wrote into the name a ship database will answer to.
 *
 * <p>The cases here are the ones checked against the live source, not invented: it matches
 * the name as a literal substring, so {@code MV HACI HILMI} and {@code M/V GEISE} return
 * nothing at all while {@code HACI HILMI} and {@code GEISE} return the hull, and
 * {@code HACI HILMI II} returns nothing where {@code HACI HILMI-II} returns her. Every one of
 * those is a ship this desk would have been told does not exist.
 */
class VesselNameQueryTest {

    @Test
    void takesTheMarketsPrefixOffTheFrontOfTheName() {
        // The whole reason the search found nothing: the source holds "HACI HILMI-II", and
        // no ship anywhere is called "MV HACI HILMI-II".
        assertThat(VesselNameQuery.clean("MV HACI HILMI-II")).isEqualTo("HACI HILMI-II");
        assertThat(VesselNameQuery.clean("M/V GEISE")).isEqualTo("GEISE");
        assertThat(VesselNameQuery.clean("m.v. lady meral")).isEqualTo("lady meral");
        assertThat(VesselNameQuery.clean("MT BOW SPRING")).isEqualTo("BOW SPRING");
    }

    @Test
    void leavesANameThatMerelyStartsLikeAPrefixAlone() {
        // A prefix is a word, not a few letters. "MVK ..." and "MSC ..." are ships.
        assertThat(VesselNameQuery.clean("MVK ATLANTIC")).isEqualTo("MVK ATLANTIC");
        assertThat(VesselNameQuery.clean("MSC PALOMA")).isEqualTo("MSC PALOMA");
    }

    @Test
    void cutsTheHistorySomebodyTypedIntoTheNameField() {
        // The shape V11 pulled 299 rows out of. No database holds a ship of this name.
        assertThat(VesselNameQuery.clean("LOIRE RIVER/ EX AMIKO")).isEqualTo("LOIRE RIVER");
        assertThat(VesselNameQuery.clean("ELEMENTS (EX KATERINA)")).isEqualTo("ELEMENTS");
        assertThat(VesselNameQuery.clean("GEISE EX SOMETHING")).isEqualTo("GEISE");
    }

    @Test
    void collapsesTheWhitespaceAWrappedCircularLeaves() {
        assertThat(VesselNameQuery.clean("  LADY   MERAL ")).isEqualTo("LADY MERAL");
    }

    @Test
    void keepsTheRawNameRatherThanReturningNothing() {
        // A hull genuinely recorded as "MV" is not a name this can improve, and sending the
        // original at least fails honestly rather than searching for an empty string.
        assertThat(VesselNameQuery.clean("MV")).isEqualTo("MV");
        assertThat(VesselNameQuery.clean("   ")).isNull();
        assertThat(VesselNameQuery.clean(null)).isNull();
    }

    @Test
    void offersASecondFormOnlyWhereTwoDatabasesWriteOneNameDifferently() {
        // "HACI HILMI II" finds nothing; "HACI HILMI" finds HACI HILMI-II. The suffix is
        // where the punctuation disagreement actually lives.
        assertThat(VesselNameQuery.forms("MV HACI HILMI II"))
                .containsExactly("HACI HILMI II", "HACI HILMI");
        assertThat(VesselNameQuery.forms("ATLANTIC BREEZE-2"))
                .containsExactly("ATLANTIC BREEZE-2", "ATLANTIC BREEZE");
    }

    @Test
    void doesNotLoosenAwayAWordThatCarriesMeaning() {
        // Dropping tokens until something answers is how a search for SEA STAR comes back
        // with twenty hulls called SEA — noise a person then has to read.
        assertThat(VesselNameQuery.forms("SEA STAR")).containsExactly("SEA STAR");
        assertThat(VesselNameQuery.forms("LADY MERAL")).containsExactly("LADY MERAL");
        // And never down to a stub: what is left has to still be a question about this ship.
        assertThat(VesselNameQuery.forms("SUN II")).containsExactly("SUN II");
    }

    @Test
    void asksAtMostTwice() {
        // Each form is a request against somebody else's server.
        assertThat(VesselNameQuery.forms("M/V ELEMENTS III (EX KATERINA)")).hasSizeLessThanOrEqualTo(2);
    }
}
