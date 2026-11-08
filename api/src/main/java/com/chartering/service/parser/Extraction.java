package com.chartering.service.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

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
        /**
         * The firm that signed it, in {@link CompanyStyleReader.Style}'s shape. Absent from a
         * model trained before the company section existed, which answers with {@code broker}
         * instead - so both are read, and {@link CompanyStyleReader#readWithModel} decides.
         */
        ExtractedCompany company,
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
     * MV, M/V, M.V., MT, MS, SS and their spellings, standing alone in front of a name.
     *
     * <p>Narrower than {@code VesselNameQuery}'s prefix on purpose. That one shapes a search
     * and may drop TUG or BARGE to find more; this one decides what is stored as her name, and
     * a tug called "TUG ATLAS" on her papers keeps it. Anchored and ended by a word boundary,
     * so a name that merely starts with the letters ("MVK ATLANTIC", "MTS VALOUR") is untouched.
     */
    private static final Pattern VESSEL_PREFIX = Pattern.compile(
            "^\\s*(?:m[./\\s]?[vts]|s[./\\s]?s)\\b[\\s./:-]*", Pattern.CASE_INSENSITIVE);

    /**
     * A vessel's name with its type prefix taken off, or the name as given when the prefix is
     * all there is - a reading called just "MV" is better kept than turned into no ship.
     */
    public static String vesselName(String raw) {
        if (raw == null) return null;
        String cleaned = VESSEL_PREFIX.matcher(raw).replaceFirst("").strip();
        return cleaned.isEmpty() ? raw : cleaned;
    }

    /**
     * The same, fitted to a column of {@code max} characters, ending in an ellipsis where it
     * was cut.
     *
     * <p>The cargo's "as written" columns are sized for what brokers usually type, and one
     * broker's terms line ran to 231 characters against a 200 column: the update failed, the
     * transaction with it, and the merge could not be answered at all. Cutting is the lesser
     * loss - the full wording is still in the email the cargo links to, one click away - and
     * the ellipsis says the field was cut rather than letting it pass for the whole of it.
     */
    public static String text(String value, int max) {
        String t = text(value);
        return t == null || t.length() <= max ? t : t.substring(0, max - 1).stripTrailing() + "\u2026";
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

        /**
         * The name without the "MV" in front of it, from the moment it is read.
         *
         * <p>A model (the hosted ones above all) hands back "MV Queen Vivian" or "M/V DOLPHIN
         * 505" as the name, and the prefix is not part of it: it is what the ship is, not what
         * she is called. Left on, it reached everything a name reaches - the exact-name match
         * found nothing, so a hull on file came back as a new ship; a hull matched by IMO raised
         * a rename row "DOLPHIN 505 -> M/V DOLPHIN 505" for a person to decline; and a hull
         * created from the reading was stored under it. Cleaning it here, in the record, rather
         * than at each of those places is what makes it one rule: the sweep, the paste and a
         * stored payload read back all deserialise through this constructor.
         * {@code raw_json} keeps the model's own string, so what the model said is still on file.
         */
        public ExtractedVessel {
            name = vesselName(name);
        }

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

    /**
     * The model's reading of the signature block - field for field the prompt's company
     * section and {@code parser/extraction-schema.json}, which is what makes it land here
     * without a translation layer. "Not in the block" is {@code ""} and an empty list.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedCompany(
            String name,
            String website,
            String city,
            String country,
            String address,
            List<ExtractedPerson> people,
            List<ExtractedContact> contacts) {

        public List<ExtractedPerson> peopleOrEmpty() {
            return people == null ? List.of() : people;
        }

        public List<ExtractedContact> contactsOrEmpty() {
            return contacts == null ? List.of() : contacts;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedPerson(String fullName, String title, String jobTitle) {
    }

    /** {@code kind} is email or phone; {@code label} is phones only, the contact form's four words. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedContact(String kind, String value, String label, String personName) {
    }
}
