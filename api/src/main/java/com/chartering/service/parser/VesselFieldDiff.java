package com.chartering.service.parser;

import com.chartering.model.Vessel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * What an email says about a ship, against what this database says.
 *
 * <p><b>Two outcomes, and separating them is what keeps the review queue readable.</b>
 *
 * <ul>
 *   <li><b>A gap fill</b> — the column is empty and the email has a figure. Written straight
 *       away, without asking. This is the contacts importer's rule ("a matched record is
 *       never overwritten, only gap-filled") applied to hulls, and here it carries most of
 *       the traffic: half this fleet has no gear recorded and 2,355 rows have no DWCC, so
 *       stopping to ask about each would put thousands of items in a queue whose every
 *       answer is yes. Nothing is destroyed by one — the field held nothing.</li>
 *   <li><b>A conflict</b> — both sides have a value and they disagree. Queued, always. This
 *       is the only thing a parse can do that would overwrite something a person put there,
 *       and it is the screen this feature was asked for.</li>
 * </ul>
 *
 * <p><b>Numbers compare with a tolerance and text compares loosely, for the same reason:</b>
 * a queue that fires on "28,000 against 28,005" or on "2x30T CRANES" against "2 x 30 t
 * cranes" is a queue nobody reads by Thursday. The tolerance is half a percent — tight
 * enough that a real disagreement about a deadweight (they differ by hundreds of tonnes, not
 * by five) still stops, loose enough to absorb the rounding a broker does when typing.
 *
 * <p><b>Capacity is the one field that can be silently wrong, and it is the one this refuses
 * to guess at.</b> The columns are cubic metres; circulars write cbm and cbft in the same
 * week, thirty-five apart, and the model is told never to convert but to report which it saw.
 * So a stated CBFT is converted, a stated CBM is taken as it is, and a capacity with no unit
 * on it is <em>dropped</em> — not gap-filled, not queued. A guess there is a handysize
 * recorded as holding 144,000 m³, and nothing downstream would ever question it.
 */
public final class VesselFieldDiff {

    private VesselFieldDiff() {
    }

    /** Cubic feet in a cubic metre. Exact by definition of the foot; rounded where used. */
    private static final BigDecimal CBFT_PER_CBM = new BigDecimal("35.3146667");

    /** Half a percent: below a broker's rounding, above nothing worth a person's time. */
    private static final BigDecimal NUMERIC_TOLERANCE = new BigDecimal("0.005");

    /**
     * One field, and how to read it from both sides.
     *
     * @param label   what the review screen calls it — the words on the vessel's own form,
     *                so the two screens name the same thing the same way
     * @param unit    appended when a value is printed, so "7.9" reads as "7.9 m"
     */
    private record Spec(String field,
                        String label,
                        String unit,
                        Function<Vessel, Object> current,
                        Function<Extraction.ExtractedVessel, Object> incoming,
                        BiConsumer<Vessel, Object> write) {
    }

    /**
     * The particulars a position list actually carries, in the order the vessel form shows
     * them.
     *
     * <p>Deliberately not every column on {@code Vessel}. {@code owner}, {@code notes},
     * {@code confirmed} and the legacy flags are facts this desk keeps about a ship rather
     * than facts a circular reports, and a parser that wrote to them would be overwriting
     * the desk's own bookkeeping with a broker's advertising copy.
     *
     * <p>{@code name} is in the list, and it can only ever appear when the hull was matched
     * by IMO — a name match cannot disagree about the name. That case is a rename, which is
     * exactly what {@code vessel_ex_names} exists for, so accepting it is handled specially
     * by the caller: the old name is filed as a former name rather than lost.
     */
    private static final List<Spec> SPECS = buildSpecs();

    private static List<Spec> buildSpecs() {
        List<Spec> s = new ArrayList<>();
        s.add(new Spec("name", "Name", null,
                Vessel::getName, v -> Extraction.text(v.name()),
                (v, o) -> v.setName((String) o)));
        s.add(new Spec("imoNumber", "IMO", null,
                Vessel::getImoNumber, v -> IntakeResolver.normaliseImo(v.imo()),
                (v, o) -> v.setImoNumber((String) o)));
        s.add(new Spec("deadweightTonnage", "DWT", "t",
                Vessel::getDeadweightTonnage, Extraction.ExtractedVessel::dwt,
                (v, o) -> v.setDeadweightTonnage((BigDecimal) o)));
        s.add(new Spec("deadweightCargoCapacity", "DWCC", "t",
                Vessel::getDeadweightCargoCapacity, Extraction.ExtractedVessel::dwcc,
                (v, o) -> v.setDeadweightCargoCapacity((BigDecimal) o)));
        s.add(new Spec("maximumDraft", "Draft", "m",
                Vessel::getMaximumDraft, Extraction.ExtractedVessel::draft,
                (v, o) -> v.setMaximumDraft((BigDecimal) o)));
        s.add(new Spec("yearBuilt", "Built", null,
                Vessel::getYearBuilt, Extraction.ExtractedVessel::built,
                (v, o) -> v.setYearBuilt((Integer) o)));
        s.add(new Spec("vesselType", "Type", null,
                Vessel::getVesselType, v -> Extraction.text(v.vesselType()),
                (v, o) -> v.setVesselType((String) o)));
        s.add(new Spec("flag", "Flag", null,
                Vessel::getFlag, v -> Extraction.text(v.flag()),
                (v, o) -> v.setFlag((String) o)));
        s.add(new Spec("grainCapacityM3", "Grain", "m3",
                Vessel::getGrainCapacityM3, v -> cubicMetres(v.grainCapacity(), v.capacityUnit()),
                (v, o) -> v.setGrainCapacityM3((BigDecimal) o)));
        s.add(new Spec("baleCapacityM3", "Bale", "m3",
                Vessel::getBaleCapacityM3, v -> cubicMetres(v.baleCapacity(), v.capacityUnit()),
                (v, o) -> v.setBaleCapacityM3((BigDecimal) o)));
        s.add(new Spec("geared", "Geared", null,
                Vessel::getGeared, Extraction.ExtractedVessel::geared,
                (v, o) -> v.setGeared((Boolean) o)));
        s.add(new Spec("gearDescription", "Gear", null,
                Vessel::getGearDescription, v -> Extraction.text(v.gearDescription()),
                (v, o) -> v.setGearDescription((String) o)));
        s.add(new Spec("holds", "Holds", null,
                Vessel::getHolds, Extraction.ExtractedVessel::holds,
                (v, o) -> v.setHolds((Short) o)));
        s.add(new Spec("hatches", "Hatches", null,
                Vessel::getHatches, Extraction.ExtractedVessel::hatches,
                (v, o) -> v.setHatches((Short) o)));
        s.add(new Spec("grainFitted", "Grain fitted", null,
                Vessel::getGrainFitted, Extraction.ExtractedVessel::grainFitted,
                (v, o) -> v.setGrainFitted((Boolean) o)));
        s.add(new Spec("timberFitted", "Timber fitted", null,
                Vessel::getTimberFitted, Extraction.ExtractedVessel::timberFitted,
                (v, o) -> v.setTimberFitted((Boolean) o)));
        s.add(new Spec("imoFitted", "IMO fitted", null,
                Vessel::getImoFitted, Extraction.ExtractedVessel::imoFitted,
                (v, o) -> v.setImoFitted((Boolean) o)));
        s.add(new Spec("iceClass", "Ice class", null,
                Vessel::getIceClass, v -> Extraction.text(v.iceClass()),
                (v, o) -> v.setIceClass((String) o)));
        return List.copyOf(s);
    }

    /** What comparing one vessel against one reading produced. */
    public record Result(List<FieldDiff> conflicts, List<String> filled) {

        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }
    }

    /**
     * Compare, and gap-fill as a side effect.
     *
     * <p>The two happen together on purpose. They are one pass over one field list, and
     * splitting them would mean two lists that have to agree about which fields exist and
     * how a capacity is converted — which is the kind of pair that agrees until somebody
     * edits one of them.
     *
     * @param vessel written to for gap fills only; conflicts are left for a person
     */
    public static Result compare(Vessel vessel, Extraction.ExtractedVessel parsed) {
        List<FieldDiff> conflicts = new ArrayList<>();
        List<String> filled = new ArrayList<>();

        for (Spec spec : SPECS) {
            Object incoming = spec.incoming().apply(parsed);
            if (isAbsent(incoming)) continue;

            Object current = spec.current().apply(vessel);
            if (isAbsent(current)) {
                spec.write().accept(vessel, incoming);
                filled.add(spec.field());
                continue;
            }
            if (!same(current, incoming)) {
                conflicts.add(new FieldDiff(spec.field(), spec.label(),
                        print(current, spec.unit()), print(incoming, spec.unit())));
            }
        }
        return new Result(List.copyOf(conflicts), List.copyOf(filled));
    }

    /**
     * Write the fields a person ticked, and only those.
     *
     * <p>Re-derived from the extraction rather than from the {@link FieldDiff} strings, so what
     * is written is the value that was compared and not a parse of how it was printed.
     *
     * @return the fields actually changed, which can be fewer than were asked for if the
     *         record moved since the item was raised
     */
    public static List<String> applySelected(Vessel vessel,
                                             Extraction.ExtractedVessel parsed,
                                             Collection<String> fields) {
        List<String> written = new ArrayList<>();
        for (Spec spec : SPECS) {
            if (!fields.contains(spec.field())) continue;
            Object incoming = spec.incoming().apply(parsed);
            if (isAbsent(incoming)) continue;
            if (same(spec.current().apply(vessel), incoming)) continue;
            spec.write().accept(vessel, incoming);
            written.add(spec.field());
        }
        return written;
    }

    /** The label for a field name, for a log line that reads as English. */
    public static String labelOf(String field) {
        return SPECS.stream().filter(s -> s.field().equals(field))
                .map(Spec::label).findFirst().orElse(field);
    }

    // --------------------------------------------------------------- internals

    /**
     * A capacity in cubic metres, or null when it cannot be known.
     *
     * <p>Null on a missing unit is the whole point — see the class comment. It is also null
     * on a unit nobody recognises, which is the same situation wearing a different word.
     */
    private static BigDecimal cubicMetres(BigDecimal value, String unit) {
        if (value == null) return null;
        String u = Extraction.text(unit);
        if (u == null) return null;
        String normalised = u.replaceAll("[^A-Za-z]", "").toUpperCase();
        return switch (normalised) {
            case "CBM", "M3", "CBMS", "CUM" -> value;
            case "CBFT", "CFT", "CBF", "FT3", "CUFT" ->
                    value.divide(CBFT_PER_CBM, new MathContext(9, RoundingMode.HALF_UP));
            default -> null;
        };
    }

    /**
     * "Not stated", whatever the type says it with.
     *
     * <p>Three spellings, and the third is the one that matters against this database. A
     * string says it with {@code ""} and a box says it with {@code null} — but the older
     * rows here say it with <b>zero</b>, which is the convention the existing figures were
     * loaded under and is why {@code Vessel} documents null and 0 as meaning the same thing
     * for a capacity. Reading a stored 0 as a value turns "DWCC 0 t against 6,750 t" into a
     * disagreement for a person to arbitrate, when it is an empty column and a figure — and
     * there are thousands of them, so it would be most of the queue.
     *
     * <p>Safe because none of the fields this class writes can truthfully be zero: a ship
     * with no deadweight, no draft, no holds or built in the year 0 is not a ship. It
     * applies to the incoming side too, for the same reason — the model is told never to
     * write 0 for "not stated", and a 0 that arrives anyway is a misreading rather than a
     * hull that displaces nothing.
     */
    private static boolean isAbsent(Object value) {
        if (value == null) return true;
        if (value instanceof String s) return s.isBlank();
        if (value instanceof BigDecimal d) return d.signum() == 0;
        if (value instanceof Number n) return n.longValue() == 0;
        return false;
    }

    private static boolean same(Object current, Object incoming) {
        if (current instanceof BigDecimal a && incoming instanceof BigDecimal b) {
            return withinTolerance(a, b);
        }
        if (current instanceof Number a && incoming instanceof Number b) {
            // Integers and shorts — a build year and a hold count are exact or they differ.
            return a.longValue() == b.longValue();
        }
        if (current instanceof String a && incoming instanceof String b) {
            return loose(a).equals(loose(b));
        }
        return current.equals(incoming);
    }

    /**
     * Equal within half a percent, and equal outright at zero.
     *
     * <p>Relative rather than absolute because the same rule has to serve a draft of 7.9 and
     * a deadweight of 28,000: a fixed epsilon that forgives five tonnes would forgive five
     * metres of draft.
     */
    private static boolean withinTolerance(BigDecimal a, BigDecimal b) {
        if (a.compareTo(b) == 0) return true;
        BigDecimal larger = a.abs().max(b.abs());
        if (larger.signum() == 0) return true;
        BigDecimal difference = a.subtract(b).abs();
        return difference.divide(larger, new MathContext(9, RoundingMode.HALF_UP))
                .compareTo(NUMERIC_TOLERANCE) <= 0;
    }

    /** Case, spacing and punctuation dropped: "2x30T CRANES" is "2 x 30 t cranes". */
    private static String loose(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String print(Object value, String unit) {
        if (value == null) return null;
        String text;
        if (value instanceof BigDecimal d) {
            // Trailing zeros stripped so 7.90 and 7.9 do not read as two different drafts.
            text = d.stripTrailingZeros().toPlainString();
        } else if (value instanceof Boolean b) {
            text = b ? "yes" : "no";
        } else {
            text = value.toString();
        }
        return unit == null ? text : text + " " + unit;
    }

    /** Every field this can write, for a request to be validated against. */
    public static Map<String, String> fields() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Spec s : SPECS) out.put(s.field(), s.label());
        return out;
    }
}
