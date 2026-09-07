package com.chartering.service.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the model gives back, as Java.
 *
 * <p><b>Field for field this is {@code AnalysisAnnotationTemplates}</b>, and that is the
 * whole reason it can be this thin. The corpus was annotated in one shape, the model was
 * trained to produce that shape, {@code parser/extraction-schema.json} constrains generation
 * to it, and the shape was itself built by naming columns in {@code Cargo}, {@code Vessel}
 * and {@code VesselPosition}. So there is no translation layer here and there must not
 * become one: a second set of names for the same facts is exactly what a translation layer
 * grows out of, and it would drift from the corpus the first time either side was edited
 * alone.
 *
 * <p><b>Two conventions travel with the values and both matter downstream.</b> A field the
 * email did not state arrives as {@code ""} for text and {@code null} for numbers and flags
 * — a string field cannot say null and a boolean cannot say empty, so "not stated" is
 * written the only way each type allows. And {@code false} is never "not stated": it is a
 * claim that the ship lacks the thing, which {@code MatchScorer} reads as a FAIL. Everything
 * that consumes these records therefore treats blank as absent and keeps null as null, which
 * is what {@link #text} and the nullable boxes below are for.
 *
 * <p>{@code ignoreUnknown} throughout: the extraction template is still moving, and a model
 * trained on a newer one that returns a field this build has never heard of should be read
 * for the twenty-eight fields it shares rather than rejected for the one it does not.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Extraction(
        /** cargo_offer, vessel_opening, mixed, other — the model's own classification. */
        String type,
        List<ExtractedCargo> cargoes,
        List<ExtractedVessel> vessels,
        Broker broker,
        /** Only present on "other", and only ever shown to a person. */
        String summary) {

    /** Never null, so callers loop rather than null-check. An "other" email has neither. */
    public List<ExtractedCargo> cargoesOrEmpty() {
        return cargoes == null ? List.of() : cargoes;
    }

    public List<ExtractedVessel> vesselsOrEmpty() {
        return vessels == null ? List.of() : vessels;
    }

    /**
     * Blank as absent, in one place.
     *
     * <p>Every text field in this file goes through it. The model says "not stated" with an
     * empty string and the database says it with NULL, and a column holding {@code ""} is a
     * column that is neither — it fails an {@code is null} test and prints as a blank on
     * screen, so it looks like data to a query and like nothing to a person.
     */
    public static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * A charterer's requirement as it arrived. Maps onto {@code Cargo} column for column,
     * except {@code charterer}, which is a company name the email used and this application
     * may or may not have a row for.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedCargo(
            String commodity,
            BigDecimal quantity,
            String quantityUnit,
            String quantityTolerance,
            BigDecimal quantityMin,
            BigDecimal quantityMax,
            BigDecimal stowageFactor,
            String loadPort,
            String loadArea,
            String dischargePort,
            String dischargeArea,
            String laycanFrom,
            String laycanTo,
            String laycanText,
            String loadRate,
            String dischargeRate,
            BigDecimal minDwt,
            BigDecimal maxDwt,
            BigDecimal maxDraft,
            Integer maxAgeYears,
            Boolean requiresGeared,
            Boolean requiresGrainFitted,
            Boolean requiresImoFitted,
            String freightIdea,
            String commission,
            String terms,
            String charterer,
            String notes) {

        /** A cargo with no commodity is not a cargo; the model returns one on a bad reading. */
        public boolean isUsable() {
            return text(commodity) != null;
        }
    }

    /**
     * One vessel's open position: a {@code Vessel} and the {@code VesselPosition} reporting
     * her, in one object because one paragraph of a circular carries both.
     *
     * <p>{@code capacityUnit} is why {@code grainCapacity} cannot be read on its own. Brokers
     * write cbft and cbm in the same week, a factor of thirty-five apart, and the model is
     * told never to convert — so the unit is part of the number and a caller that ignores it
     * will store a 144,000 m³ handysize.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedVessel(
            String name,
            String imo,
            String vesselType,
            BigDecimal dwt,
            BigDecimal dwcc,
            BigDecimal draft,
            Integer built,
            String flag,
            BigDecimal grainCapacity,
            BigDecimal baleCapacity,
            String capacityUnit,
            Boolean geared,
            String gearDescription,
            Short holds,
            Short hatches,
            Boolean grainFitted,
            Boolean timberFitted,
            Boolean imoFitted,
            String iceClass,
            String openPort,
            String openArea,
            String openFrom,
            String openTo,
            String openText,
            String lastCargo,
            String cargoPreferences,
            String notes) {

        /** A position with no ship on it cannot be filed against anything. */
        public boolean isUsable() {
            return text(name) != null;
        }
    }

    /**
     * Who sent it, as the email signs itself.
     *
     * <p>Read but rarely needed: the sender is already resolved to a contact, a person and a
     * company by the mail sync, from the envelope rather than from the signature block, and
     * that link is the better one. This is the fallback for a circular forwarded by somebody
     * else, and evidence when the two disagree.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Broker(String company, String person, String email) {
    }
}
