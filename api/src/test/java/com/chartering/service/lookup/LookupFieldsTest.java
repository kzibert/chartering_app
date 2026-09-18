package com.chartering.service.lookup;

import com.chartering.model.Vessel;
import com.chartering.service.VesselTypes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a public database is allowed to put in each column, and in whose terms.
 *
 * <p>The type column is the one with a trap in it. It holds a category from a fixed list —
 * {@link VesselTypes#CANONICAL} — and a tracking page writes prose: "General Cargo Ship",
 * "Bulk Carrier". Those are not categories and they are not disagreements with one either,
 * which is exactly why {@code VesselFieldDiff} has no type field and why a hull created from
 * a reading gets {@code VesselTypes.canonical(wording)}. A lookup writing the wording
 * straight through would be the one door left open into the column that
 * {@code POST /vessels/types/fix} had to be written to clean out.
 */
class LookupFieldsTest {

    private static VesselParticulars candidate(String type) {
        return new VesselParticulars("9255074", "DEMA M", type, "Panama", 2003,
                null, new BigDecimal("32259"), null, null,
                "https://example.example/vessels/9255074");
    }

    private static Vessel blank() {
        Vessel v = new Vessel();
        v.setId(1L);
        return v;
    }

    @Test
    void offersTheCategoryAWordingNamesRatherThanTheWording() {
        List<LookupFields.Proposal> proposals =
                LookupFields.proposals(blank(), candidate("General Cargo Ship"));

        LookupFields.Proposal type = proposals.stream()
                .filter(p -> p.field().equals("vesselType")).findFirst().orElseThrow();
        assertThat(type.incoming()).isEqualTo(VesselTypes.SEA_TYPE);
    }

    @Test
    void writesTheCategoryAndNeverTheSourcesProse() {
        Vessel v = blank();

        List<String> written =
                LookupFields.apply(v, candidate("Bulk Carrier"), Set.of("vesselType"));

        assertThat(written).containsExactly("vesselType");
        assertThat(v.getVesselType()).isEqualTo(VesselTypes.SEA_TYPE);
        assertThat(VesselTypes.isCanonical(v.getVesselType())).isTrue();
    }

    /**
     * A wording naming no category is dropped, not guessed at.
     *
     * <p>Nothing is offered and nothing is written: the source said something this column has
     * no way to hold, and inventing a category from it would be worse than leaving the hull
     * with no type, which already means "not on file".
     */
    @Test
    void dropsAWordingThatNamesNoCategory() {
        Vessel v = blank();

        assertThat(LookupFields.proposals(v, candidate("Self-Discharging Whatnot")))
                .extracting(LookupFields.Proposal::field)
                .doesNotContain("vesselType");
        assertThat(LookupFields.apply(v, candidate("Self-Discharging Whatnot"),
                Set.of("vesselType"))).isEmpty();
        assertThat(v.getVesselType()).isNull();
    }

    /**
     * A record already holding the right category is not a disagreement with the prose.
     *
     * <p>Before the wording was reduced, "General Cargo Ship" against SEA TYPE read as a
     * difference and offered to overwrite a category with a sentence.
     */
    @Test
    void doesNotCallAMatchingCategoryADisagreement() {
        Vessel v = blank();
        v.setVesselType(VesselTypes.SEA_TYPE);

        LookupFields.Proposal type =
                LookupFields.proposals(v, candidate("General Cargo Ship")).stream()
                        .filter(p -> p.field().equals("vesselType")).findFirst().orElseThrow();

        assertThat(type.differs()).isFalse();
        assertThat(LookupFields.apply(v, candidate("General Cargo Ship"), Set.of("vesselType")))
                .isEmpty();
    }

    /** The other four fields are untouched by any of this. */
    @Test
    void leavesTheFiguresAlone() {
        Vessel v = blank();

        List<String> written = LookupFields.apply(v, candidate("General Cargo Ship"),
                Set.of("imoNumber", "deadweightTonnage", "yearBuilt", "flag"));

        assertThat(written).containsExactlyInAnyOrder(
                "imoNumber", "deadweightTonnage", "yearBuilt", "flag");
        assertThat(v.getImoNumber()).isEqualTo("9255074");
        assertThat(v.getDeadweightTonnage()).isEqualByComparingTo("32259");
        assertThat(v.getYearBuilt()).isEqualTo(2003);
        assertThat(v.getFlag()).isEqualTo("Panama");
    }
}
