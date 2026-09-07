package com.chartering.service.parser;

import com.chartering.model.Vessel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line between what a parse may write on its own and what it has to ask about.
 *
 * <p>Both halves are worth testing and the gap fill is the one that carries the traffic: half
 * this fleet has no gear recorded, so a rule that queued those instead of writing them would
 * produce thousands of review items whose every answer is yes, and the queue would stop being
 * read. The conflicts are the other half — they are the only thing here that would overwrite
 * something a person put on file.
 */
class VesselFieldDiffTest {

    private static Extraction.ExtractedVessel reading() {
        return new Extraction.ExtractedVessel(
                "PACIFIC DAWN", "", "", null, null, null, null, "",
                null, null, "", null, "", null, null, null, null, null, "",
                "", "", "", "", "", "", "", "");
    }

    private static Extraction.ExtractedVessel with(Extraction.ExtractedVessel v,
                                                   java.util.function.UnaryOperator<Builder> edit) {
        return edit.apply(new Builder(v)).build();
    }

    /** A builder over the record, so a test can name one field instead of twenty-seven. */
    private static final class Builder {
        private BigDecimal dwt;
        private BigDecimal dwcc;
        private BigDecimal draft;
        private Integer built;
        private Boolean geared;
        private String gearDescription = "";
        private Short holds;
        private BigDecimal grainCapacity;
        private String capacityUnit = "";
        private String imo = "";
        private String name;

        private Builder(Extraction.ExtractedVessel v) {
            this.name = v.name();
        }

        Builder dwt(String v) {
            dwt = new BigDecimal(v);
            return this;
        }

        Builder dwcc(String v) {
            dwcc = new BigDecimal(v);
            return this;
        }

        Builder draft(String v) {
            draft = new BigDecimal(v);
            return this;
        }

        Builder built(int v) {
            built = v;
            return this;
        }

        Builder geared(Boolean v) {
            geared = v;
            return this;
        }

        Builder gear(String v) {
            gearDescription = v;
            return this;
        }

        Builder holds(int v) {
            holds = (short) v;
            return this;
        }

        Builder grain(String value, String unit) {
            grainCapacity = new BigDecimal(value);
            capacityUnit = unit;
            return this;
        }

        Builder imo(String v) {
            imo = v;
            return this;
        }

        Builder name(String v) {
            name = v;
            return this;
        }

        Extraction.ExtractedVessel build() {
            return new Extraction.ExtractedVessel(
                    name, imo, "", dwt, dwcc, draft, built, "",
                    grainCapacity, null, capacityUnit, geared, gearDescription, holds, null,
                    null, null, null, "", "", "", "", "", "", "", "", "");
        }
    }

    @Test
    void fillsAnEmptyColumnWithoutAsking() {
        Vessel v = new Vessel();
        v.setName("PACIFIC DAWN");

        VesselFieldDiff.Result result =
                VesselFieldDiff.compare(v, with(reading(), b -> b.dwt("28500").geared(true).holds(4)));

        assertThat(result.hasConflicts()).isFalse();
        assertThat(result.filled()).contains("deadweightTonnage", "geared", "holds");
        assertThat(v.getDeadweightTonnage()).isEqualByComparingTo("28500");
        assertThat(v.getGeared()).isTrue();
        assertThat(v.getHolds()).isEqualTo((short) 4);
    }

    @Test
    void raisesAConflictAndLeavesTheRecordAloneWhenBothSidesSpeak() {
        Vessel v = new Vessel();
        v.setName("PACIFIC DAWN");
        v.setDeadweightTonnage(new BigDecimal("28500"));

        VesselFieldDiff.Result result =
                VesselFieldDiff.compare(v, with(reading(), b -> b.dwt("32000")));

        assertThat(result.conflicts()).extracting(FieldDiff::field).containsExactly("deadweightTonnage");
        assertThat(result.conflicts().get(0).current()).isEqualTo("28500 t");
        assertThat(result.conflicts().get(0).incoming()).isEqualTo("32000 t");
        // The whole point: nothing was written.
        assertThat(v.getDeadweightTonnage()).isEqualByComparingTo("28500");
    }

    @Test
    void forgivesTheRoundingABrokerDoesWhenTyping() {
        Vessel v = new Vessel();
        v.setDeadweightTonnage(new BigDecimal("28500"));

        // Within half a percent. A queue that fired on this would be a queue nobody reads.
        assertThat(VesselFieldDiff.compare(v, with(reading(), b -> b.dwt("28600"))).hasConflicts())
                .isFalse();
        // Hundreds of tonnes out is a real disagreement.
        assertThat(VesselFieldDiff.compare(v, with(reading(), b -> b.dwt("29500"))).hasConflicts())
                .isTrue();
    }

    @Test
    void readsGearWrittenAnyWayAsTheSameGear() {
        Vessel v = new Vessel();
        v.setGearDescription("2x30T CRANES");

        assertThat(VesselFieldDiff.compare(v, with(reading(), b -> b.gear("2 x 30 t cranes")))
                .hasConflicts()).isFalse();
        assertThat(VesselFieldDiff.compare(v, with(reading(), b -> b.gear("4x12.5T CRANES")))
                .hasConflicts()).isTrue();
    }

    @Test
    void convertsAStatedCubicFeetCapacityAndRefusesAnUnlabelledOne() {
        Vessel filled = new Vessel();
        VesselFieldDiff.compare(filled, with(reading(), b -> b.grain("144000", "CBFT")));
        // 144,000 cbft is about 4,077 m3 - not 144,000.
        assertThat(filled.getGrainCapacityM3()).isNotNull();
        assertThat(filled.getGrainCapacityM3().doubleValue()).isBetween(4070.0, 4085.0);

        Vessel asWritten = new Vessel();
        VesselFieldDiff.compare(asWritten, with(reading(), b -> b.grain("4077", "cbm")));
        assertThat(asWritten.getGrainCapacityM3()).isEqualByComparingTo("4077");

        // No unit, no guess. Thirty-five times wrong is worse than not knowing, and nothing
        // downstream would ever question it.
        Vessel unlabelled = new Vessel();
        VesselFieldDiff.Result result =
                VesselFieldDiff.compare(unlabelled, with(reading(), b -> b.grain("144000", "")));
        assertThat(unlabelled.getGrainCapacityM3()).isNull();
        assertThat(result.filled()).doesNotContain("grainCapacityM3");
        assertThat(result.hasConflicts()).isFalse();
    }

    @Test
    void readsAStoredZeroAsAnEmptyColumnRatherThanAFigure() {
        Vessel v = new Vessel();
        v.setName("PACIFIC DAWN");
        // How the older rows here say "not on file" — the convention the existing figures
        // were loaded under. Thousands of them carry it.
        v.setDeadweightCargoCapacity(BigDecimal.ZERO);
        v.setBaleCapacityM3(BigDecimal.ZERO);

        VesselFieldDiff.Result result = VesselFieldDiff.compare(v,
                with(reading(), b -> b.dwcc("6750")));

        // A gap fill, not a disagreement for somebody to arbitrate.
        assertThat(result.hasConflicts()).isFalse();
        assertThat(result.filled()).contains("deadweightCargoCapacity");
        assertThat(v.getDeadweightCargoCapacity()).isEqualByComparingTo("6750");
    }

    @Test
    void neverWritesAZeroTheModelReturnedByMistake() {
        Vessel v = new Vessel();
        v.setName("PACIFIC DAWN");
        v.setDeadweightTonnage(new BigDecimal("28500"));

        // The model is told never to write 0 for "not stated". One that arrives anyway is a
        // misreading, not a hull that displaces nothing.
        VesselFieldDiff.Result result = VesselFieldDiff.compare(v, with(reading(), b -> b.dwt("0")));

        assertThat(result.hasConflicts()).isFalse();
        assertThat(result.filled()).isEmpty();
        assertThat(v.getDeadweightTonnage()).isEqualByComparingTo("28500");
    }

    @Test
    void writesOnlyTheFieldsThatWereTicked() {
        Vessel v = new Vessel();
        v.setDeadweightTonnage(new BigDecimal("28500"));
        v.setMaximumDraft(new BigDecimal("9.5"));

        Extraction.ExtractedVessel parsed =
                with(reading(), b -> b.dwt("32000").draft("10.2"));

        List<String> written =
                VesselFieldDiff.applySelected(v, parsed, List.of("deadweightTonnage"));

        assertThat(written).containsExactly("deadweightTonnage");
        assertThat(v.getDeadweightTonnage()).isEqualByComparingTo("32000");
        // Not ticked, not written — which is the entire reason the screen has ticks.
        assertThat(v.getMaximumDraft()).isEqualByComparingTo("9.5");
    }

    @Test
    void neverReadsAnAbsentFieldAsAClaim() {
        Vessel v = new Vessel();
        // Named, because a matched hull always is — an unnamed one would gap-fill her name
        // from the reading and drown out what this test is about.
        v.setName("PACIFIC DAWN");
        v.setGeared(true);
        v.setDeadweightTonnage(new BigDecimal("28500"));

        // The email says nothing about either. That is not a disagreement and not a gap fill.
        VesselFieldDiff.Result result = VesselFieldDiff.compare(v, reading());

        assertThat(result.hasConflicts()).isFalse();
        assertThat(result.filled()).isEmpty();
        assertThat(v.getGeared()).isTrue();
    }

    @Test
    void treatsAnImoOfTheWrongShapeAsNothingSaid() {
        Vessel v = new Vessel();
        VesselFieldDiff.compare(v, with(reading(), b -> b.imo("IMO 9123456")));
        assertThat(v.getImoNumber()).isEqualTo("9123456");

        Vessel other = new Vessel();
        VesselFieldDiff.compare(other, with(reading(), b -> b.imo("91234")));
        assertThat(other.getImoNumber()).isNull();
    }

    @Test
    void surfacesARenameAsAConflictOnTheNameItself() {
        Vessel v = new Vessel();
        v.setName("AMIKO");

        VesselFieldDiff.Result result =
                VesselFieldDiff.compare(v, with(reading(), b -> b.name("LOIRE RIVER")));

        // Only reachable when the hull was matched by IMO, which is exactly the rename case.
        assertThat(result.conflicts()).extracting(FieldDiff::field).contains("name");
        assertThat(v.getName()).isEqualTo("AMIKO");
    }
}
