package com.chartering.service.parser;

import com.chartering.model.Vessel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        private BigDecimal baleCapacity;
        private String capacityUnit = "";
        private String imo = "";
        private String vesselType = "";
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

        Builder bale(String value) {
            baleCapacity = new BigDecimal(value);
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

        Builder type(String v) {
            vesselType = v;
            return this;
        }

        Extraction.ExtractedVessel build() {
            return new Extraction.ExtractedVessel(
                    name, imo, vesselType, dwt, dwcc, draft, built, "",
                    grainCapacity, baleCapacity, capacityUnit, geared, gearDescription, holds, null,
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
    void readsAnUnlabelledCapacityOffTheShipsSize() {
        // 144,000 on a 3,400-tonner is 42 per tonne: cubic feet, though nothing said so.
        Vessel fromTheEmail = new Vessel();
        VesselFieldDiff.compare(fromTheEmail, with(reading(), b -> b.dwt("3400").grain("144000", "")));
        assertThat(fromTheEmail.getGrainCapacityM3().doubleValue()).isBetween(4070.0, 4085.0);

        // The email gives no size, but her record does.
        Vessel onFile = new Vessel();
        onFile.setDeadweightTonnage(new BigDecimal("3400"));
        VesselFieldDiff.compare(onFile, with(reading(), b -> b.grain("4200", "")));
        assertThat(onFile.getGrainCapacityM3()).isEqualByComparingTo("4200");
    }

    @Test
    void theShipsSizeOverridesAUnitLabelItContradicts() {
        // "GRAIN 144,000 CBM" on a 3,400-tonner: forty times what the hull could hold.
        Vessel v = new Vessel();
        VesselFieldDiff.compare(v, with(reading(), b -> b.dwt("3400").grain("144000", "cbm")));
        assertThat(v.getGrainCapacityM3().doubleValue()).isBetween(4070.0, 4085.0);
    }

    @Test
    void neverComparesOrFillsTheVesselType() {
        Vessel onFile = new Vessel();
        onFile.setVesselType("SEA TYPE BOX SHAPE");
        VesselFieldDiff.Result result = VesselFieldDiff.compare(onFile,
                with(reading(), b -> b.type("GENERAL-DRY CARGO VESSEL / DOUBLE SKIN/BOX")));
        assertThat(result.hasConflicts()).isFalse();
        assertThat(onFile.getVesselType()).isEqualTo("SEA TYPE BOX SHAPE");

        Vessel blank = new Vessel();
        VesselFieldDiff.compare(blank, with(reading(), b -> b.type("GENERAL CARGO")));
        assertThat(blank.getVesselType()).isNull();
    }

    @Test
    void previewWritesNothingAndOffersEmptyColumnsAsRows() {
        Vessel v = new Vessel();
        v.setName("PACIFIC DAWN");
        v.setDeadweightTonnage(new BigDecimal("3400"));
        v.setHolds((short) 2);

        // A waiting item raised before capacities were judged by size: no unit stated.
        VesselFieldDiff.Result rows = VesselFieldDiff.preview(v,
                with(reading(), b -> b.holds(3).grain("144000", "")));

        assertThat(rows.conflicts()).extracting(FieldDiff::field).containsExactly("grainCapacityM3", "holds");
        assertThat(rows.conflicts().get(0).current()).isNull();
        assertThat(rows.conflicts().get(0).incoming()).startsWith("407");
        // Looking wrote nothing.
        assertThat(v.getGrainCapacityM3()).isNull();
        assertThat(v.getHolds()).isEqualTo((short) 2);

        // And ticking an empty column writes it.
        VesselFieldDiff.applySelected(v, with(reading(), b -> b.grain("144000", "")), java.util.List.of("grainCapacityM3"));
        assertThat(v.getGrainCapacityM3().doubleValue()).isBetween(4070.0, 4085.0);
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

    /**
     * JELENA, and the reason each figure is now asked about its own size.
     *
     * <p>One broker writes her grain in cubic metres (8,267) and her bale in cubic feet
     * (291,000) in the same paragraph. Judging the pair on grain settled both as cbm, so her
     * bale read as 291,000 m3 - fifty times what a 5,700-tonner holds - and that absurdity was
     * put to a person to arbitrate every morning for a week. Read one at a time, the bale
     * converts to 8,240 m3, which is exactly what the record already says: no question at all.
     */
    @Test
    void readsGrainAndBaleInDifferentUnitsWhenTheShipsSizeSaysSo() {
        Vessel jelena = new Vessel();
        jelena.setId(3690L);
        jelena.setDeadweightCargoCapacity(new BigDecimal("5000"));
        jelena.setGrainCapacityM3(new BigDecimal("8267"));
        jelena.setBaleCapacityM3(new BigDecimal("8240"));

        VesselFieldDiff.Result result = VesselFieldDiff.compare(jelena,
                with(reading(), b -> b.dwcc("5000").grain("8267", "CBM").bale("291000")));

        assertThat(result.conflicts()).isEmpty();
        assertThat(jelena.getBaleCapacityM3()).isEqualByComparingTo("8240");
    }

    /**
     * What the one-unit rule was for, kept as the fallback.
     *
     * <p>A bale figure its own size cannot place - 100,000 against a 28,000-tonner is 3.6 per
     * tonne, too much for cubic metres and far too little for cubic feet - still gets the
     * answer the grain figure earned, rather than being dropped for want of a label neither of
     * them carries.
     */
    @Test
    void fallsBackToTheOtherCapacitysUnitWhenASizeCannotPlaceThisOne() {
        Vessel v = new Vessel();
        v.setDeadweightTonnage(new BigDecimal("28000"));

        VesselFieldDiff.Result result = VesselFieldDiff.compare(v,
                with(reading(), b -> b.dwt("28000").grain("1306000", "").bale("100000")));

        // Grain places itself as cbft; bale is inside neither band and takes grain's answer.
        assertThat(v.getGrainCapacityM3()).isEqualByComparingTo("36982");
        assertThat(v.getBaleCapacityM3()).isEqualByComparingTo("2832");
        assertThat(result.conflicts()).isEmpty();
    }

    @Test
    void writesTheValueAPersonTypedInsteadOfEitherSides() {
        Vessel v = new Vessel();
        v.setName("PACIFIC DAWN");
        v.setDeadweightTonnage(new BigDecimal("28000"));
        v.setMaximumDraft(new BigDecimal("9.5"));

        Object corrected = VesselFieldDiff.parseCorrection("maximumDraft", "7.9 m");
        List<String> written = VesselFieldDiff.applySelected(v,
                with(reading(), b -> b.draft("8.4")), List.of("maximumDraft"),
                java.util.Map.of("maximumDraft", corrected));

        assertThat(written).containsExactly("maximumDraft");
        assertThat(v.getMaximumDraft()).isEqualByComparingTo("7.9");
    }

    /**
     * A correction that cannot be the field's type is refused, not skipped.
     *
     * <p>This is the one place a human's keystrokes become a column value, so "abt 7.9" coming
     * back as null would be an accept that silently did nothing - and the reviewer would be
     * looking at a record that still reads 9.5.
     */
    @Test
    void refusesATypedValueTheColumnCannotHold() {
        assertThatThrownBy(() -> VesselFieldDiff.parseCorrection("maximumDraft", "abt 7.9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Draft");
        assertThatThrownBy(() -> VesselFieldDiff.parseCorrection("maximumDraft", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("untick");
    }

    /**
     * A settled value is recognised again through the comparison, not as text.
     *
     * <p>A broker who rounds 28,500 to 28,450 next Wednesday is reporting the figure that was
     * turned down on Tuesday, and a queue that fires on the re-rounding is the queue the whole
     * mechanism exists to stop.
     */
    @Test
    void recognisesASettledValueThroughTheSameTolerance() {
        Vessel v = new Vessel();
        v.setDeadweightTonnage(new BigDecimal("28000"));

        Extraction.ExtractedVessel wednesday = with(reading(), b -> b.dwt("28450"));
        assertThat(VesselFieldDiff.incomingValue(v, wednesday, "deadweightTonnage"))
                .isEqualTo("28450");
        assertThat(VesselFieldDiff.reportsValue(v, wednesday, "deadweightTonnage", "28500"))
                .isTrue();
        assertThat(VesselFieldDiff.reportsValue(v, wednesday, "deadweightTonnage", "31000"))
                .isFalse();
    }

    /** A capacity is stored as it was compared - in cubic metres, with no unit on it. */
    @Test
    void storesADeclinedCapacityInTheUnitItWasComparedIn() {
        Vessel jelena = new Vessel();
        jelena.setDeadweightCargoCapacity(new BigDecimal("5000"));

        String value = VesselFieldDiff.incomingValue(jelena,
                with(reading(), b -> b.dwcc("5000").bale("291000")), "baleCapacityM3");

        assertThat(value).isEqualTo("8240");
    }
}
