package com.chartering.service.parser;

import com.chartering.service.CapacityUnits;

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
import java.util.function.BiFunction;
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
 * <p><b>Capacity is the one field that can be silently wrong, so its unit is settled before
 * anything is compared.</b> The columns are cubic metres; circulars write cbm and cbft in the
 * same week, thirty-five apart, and often write neither. The unit is read off her size —
 * the deadweight the email gives, else the one already on her record — by
 * {@link CapacityUnits}: a figure plausible in exactly one unit is in that unit, whatever label
 * sat beside it, and only where the size cannot decide is a stated unit taken as written. A
 * capacity with no unit and no size to judge it by is still <em>dropped</em> — not gap-filled,
 * not queued — because that one would be a guess.
 *
 * <p><b>Grain and bale are settled one figure at a time, and they used to share one unit.</b>
 * The reasoning was that a list quotes a ship's holds in one unit throughout, so judging the
 * clearer figure and applying its answer to the other covered a figure its own size could not
 * place. It is not true of every format. One broker here writes JELENA's grain in cubic metres
 * (8,267 against 5,000 DWCC) and her bale in cubic feet (291,000) in the same paragraph: the
 * grain figure settled the pair as cbm, so her bale was read as 291,000 m³ — fifty times what a
 * 5,700-tonner holds — and that absurdity was put to a person as a disagreement to arbitrate,
 * every morning, for a week. Each figure is now asked about its own size first. What the old
 * rule bought is kept as a fallback rather than as the rule: where a figure's own size cannot
 * place it, the other capacity's answer is taken, and only then the label the text wrote.
 */
public final class VesselFieldDiff {

    private VesselFieldDiff() {
    }

    /** Half a percent: below a broker's rounding, above nothing worth a person's time. */
    private static final BigDecimal NUMERIC_TOLERANCE = new BigDecimal("0.005");

    /**
     * What a field holds, which is the one thing a string has to be read back into.
     *
     * <p>Needed because two callers now hand this class text rather than a reading: a reviewer
     * correcting a figure on the review screen, and {@code intake_field_decisions} storing a
     * value that was declined so the same one can be recognised when it arrives again. Both
     * have to end up with the value the comparison would have produced — a draft typed as
     * "7.9" has to become a number and not the string, or it would be written to a numeric
     * column as text and compared as one.
     */
    private enum Kind { TEXT, DECIMAL, INTEGER, SHORT, BOOLEAN }

    /**
     * One field, and how to read it from both sides.
     *
     * @param label    what the review screen calls it — the words on the vessel's own form,
     *                 so the two screens name the same thing the same way
     * @param unit     appended when a value is printed, so "7.9" reads as "7.9 m"
     * @param incoming takes the hull as well as the reading: a capacity cannot be read without
     *                 a size to judge its unit by, and hers is the fallback when the email
     *                 repeats her holds without repeating her deadweight
     */
    private record Spec(String field,
                        String label,
                        String unit,
                        Kind kind,
                        Function<Vessel, Object> current,
                        BiFunction<Extraction.ExtractedVessel, Vessel, Object> incoming,
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
     * <p>{@code vesselType} is left out for the same reason, and it used to be in. The column
     * holds one of {@link com.chartering.service.VesselTypes#CANONICAL}; a circular describes
     * the hull in its own words ("GENERAL-DRY CARGO VESSEL / DOUBLE SKIN/BOX"), so comparing the
     * two raised a question on every list and gap-filling wrote a new one-off type into the
     * dropdown. A new hull gets a category mapped from the wording instead — see
     * {@code IntakeService#createVessel} — and a hull on file keeps the one somebody chose.
     *
     * <p>{@code name} is in the list, and it can only ever appear when the hull was matched
     * by IMO — a name match cannot disagree about the name. That case is a rename, which is
     * exactly what {@code vessel_ex_names} exists for, so accepting it is handled specially
     * by the caller: the old name is filed as a former name rather than lost.
     */
    private static final List<Spec> SPECS = buildSpecs();

    private static List<Spec> buildSpecs() {
        List<Spec> s = new ArrayList<>();
        s.add(new Spec("name", "Name", null, Kind.TEXT,
                Vessel::getName, (v, ship) -> Extraction.text(v.name()),
                (v, o) -> v.setName((String) o)));
        s.add(new Spec("imoNumber", "IMO", null, Kind.TEXT,
                Vessel::getImoNumber, (v, ship) -> IntakeResolver.normaliseImo(v.imo()),
                (v, o) -> v.setImoNumber((String) o)));
        s.add(new Spec("deadweightTonnage", "DWT", "t", Kind.DECIMAL,
                Vessel::getDeadweightTonnage, (v, ship) -> v.dwt(),
                (v, o) -> v.setDeadweightTonnage((BigDecimal) o)));
        s.add(new Spec("deadweightCargoCapacity", "DWCC", "t", Kind.DECIMAL,
                Vessel::getDeadweightCargoCapacity, (v, ship) -> v.dwcc(),
                (v, o) -> v.setDeadweightCargoCapacity((BigDecimal) o)));
        s.add(new Spec("maximumDraft", "Draft", "m", Kind.DECIMAL,
                Vessel::getMaximumDraft, (v, ship) -> v.draft(),
                (v, o) -> v.setMaximumDraft((BigDecimal) o)));
        s.add(new Spec("yearBuilt", "Built", null, Kind.INTEGER,
                Vessel::getYearBuilt, (v, ship) -> v.built(),
                (v, o) -> v.setYearBuilt((Integer) o)));
        s.add(new Spec("flag", "Flag", null, Kind.TEXT,
                Vessel::getFlag, (v, ship) -> Extraction.text(v.flag()),
                (v, o) -> v.setFlag((String) o)));
        s.add(new Spec("grainCapacityM3", "Grain", "m3", Kind.DECIMAL,
                Vessel::getGrainCapacityM3,
                (v, ship) -> cubicMetres(v.grainCapacity(), v.baleCapacity(), v, ship),
                (v, o) -> v.setGrainCapacityM3((BigDecimal) o)));
        s.add(new Spec("baleCapacityM3", "Bale", "m3", Kind.DECIMAL,
                Vessel::getBaleCapacityM3,
                (v, ship) -> cubicMetres(v.baleCapacity(), v.grainCapacity(), v, ship),
                (v, o) -> v.setBaleCapacityM3((BigDecimal) o)));
        s.add(new Spec("geared", "Geared", null, Kind.BOOLEAN,
                Vessel::getGeared, (v, ship) -> v.geared(),
                (v, o) -> v.setGeared((Boolean) o)));
        s.add(new Spec("gearDescription", "Gear", null, Kind.TEXT,
                Vessel::getGearDescription, (v, ship) -> Extraction.text(v.gearDescription()),
                (v, o) -> v.setGearDescription((String) o)));
        s.add(new Spec("holds", "Holds", null, Kind.SHORT,
                Vessel::getHolds, (v, ship) -> v.holds(),
                (v, o) -> v.setHolds((Short) o)));
        s.add(new Spec("hatches", "Hatches", null, Kind.SHORT,
                Vessel::getHatches, (v, ship) -> v.hatches(),
                (v, o) -> v.setHatches((Short) o)));
        s.add(new Spec("grainFitted", "Grain fitted", null, Kind.BOOLEAN,
                Vessel::getGrainFitted, (v, ship) -> v.grainFitted(),
                (v, o) -> v.setGrainFitted((Boolean) o)));
        s.add(new Spec("timberFitted", "Timber fitted", null, Kind.BOOLEAN,
                Vessel::getTimberFitted, (v, ship) -> v.timberFitted(),
                (v, o) -> v.setTimberFitted((Boolean) o)));
        s.add(new Spec("imoFitted", "IMO fitted", null, Kind.BOOLEAN,
                Vessel::getImoFitted, (v, ship) -> v.imoFitted(),
                (v, o) -> v.setImoFitted((Boolean) o)));
        s.add(new Spec("iceClass", "Ice class", null, Kind.TEXT,
                Vessel::getIceClass, (v, ship) -> Extraction.text(v.iceClass()),
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
    public static Result compare(Vessel vessel, Extraction.ExtractedVessel reading) {
        List<FieldDiff> conflicts = new ArrayList<>();
        List<String> filled = new ArrayList<>();

        for (Spec spec : SPECS) {
            Object incoming = spec.incoming().apply(reading, vessel);
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
     * What a reading would change on her record as it stands now, writing nothing.
     *
     * <p><b>For a question that has been waiting.</b> An item's rows used to be the comparison
     * made the day the email arrived, stored with it. That goes stale two ways: the record moves
     * (somebody fills in her deadweight), and the rules move — a capacity read before its unit
     * was judged by her size was dropped or read thirty-five times wrong, and a stored row keeps
     * saying so. So the review screen asks this each time it opens the item, and "accept all"
     * accepts what this says rather than what was stored.
     *
     * <p>Unlike {@link #compare}, a field empty on her record is a row here, with nothing on
     * file beside it, rather than a gap already filled: nothing is written by looking, so the
     * only way such a field reaches her record is a person ticking it.
     */
    public static Result preview(Vessel vessel, Extraction.ExtractedVessel reading) {
        List<FieldDiff> rows = new ArrayList<>();
        for (Spec spec : SPECS) {
            Object incoming = spec.incoming().apply(reading, vessel);
            if (isAbsent(incoming)) continue;
            Object current = spec.current().apply(vessel);
            if (isAbsent(current)) {
                rows.add(new FieldDiff(spec.field(), spec.label(), null, print(incoming, spec.unit())));
            } else if (!same(current, incoming)) {
                rows.add(new FieldDiff(spec.field(), spec.label(),
                        print(current, spec.unit()), print(incoming, spec.unit())));
            }
        }
        return new Result(List.copyOf(rows), List.of());
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
                                             Extraction.ExtractedVessel reading,
                                             Collection<String> fields) {
        return applySelected(vessel, reading, fields, Map.of());
    }

    /**
     * The same, with the reviewer's own value where they typed one.
     *
     * <p><b>A third answer, and the screen was missing it.</b> The two it had were the record
     * and the email, and a broker's list is regularly wrong in a way that does not make the
     * record right: a capacity quoted in the wrong unit, a gear description garbled, a flag out
     * of date on both sides. Answering that took two visits — discard the item, then go and
     * edit the hull — and the second half is the one that gets forgotten.
     *
     * <p>A correction is written exactly like an accepted value, through the same typed writer,
     * so a figure typed as "7.9" lands as a number and not as text in a numeric column. What it
     * does <em>not</em> share is the skip below: an accepted value that already matches the
     * record is no change and is dropped, while a correction is a person's deliberate
     * instruction and is written even where it agrees with what is there, because the caller
     * records that it was made.
     *
     * @param corrections field to the value the reviewer typed, already read into the field's
     *                    own type by {@link #parseCorrection}
     */
    public static List<String> applySelected(Vessel vessel,
                                             Extraction.ExtractedVessel reading,
                                             Collection<String> fields,
                                             Map<String, Object> corrections) {
        List<String> written = new ArrayList<>();
        for (Spec spec : SPECS) {
            if (!fields.contains(spec.field())) continue;

            if (corrections.containsKey(spec.field())) {
                Object corrected = corrections.get(spec.field());
                if (corrected == null) continue;
                spec.write().accept(vessel, corrected);
                written.add(spec.field());
                continue;
            }

            Object incoming = spec.incoming().apply(reading, vessel);
            if (isAbsent(incoming)) continue;
            // An empty column is written like any other ticked field; comparing against it
            // would dereference nothing.
            Object current = spec.current().apply(vessel);
            if (!isAbsent(current) && same(current, incoming)) continue;
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

    // ------------------------------------------------------- what a person typed

    /**
     * A reviewer's typed value, read into the type its column holds.
     *
     * <p>Strict, and it says what it wanted. This is the one place in the feature where a
     * human's keystrokes become a database value, so "abt 7.9" coming back as null and being
     * quietly skipped would be an accept that did nothing — the failure this class avoids
     * everywhere else by re-deriving from the extraction instead of parsing display text.
     *
     * <p>Thousands separators and a trailing unit are forgiven, because the value on screen
     * beside the box carries both and the obvious thing to do is edit it in place: "8,240 m3"
     * typed back over is the same figure, and refusing it would teach nobody anything.
     *
     * @throws IllegalArgumentException when the text is blank or is not a value of that type
     */
    public static Object parseCorrection(String field, String text) {
        Spec spec = specOf(field);
        if (spec == null) {
            throw new IllegalArgumentException("There is no field called \"" + field + "\".");
        }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException(
                    "Type a value for " + spec.label() + ", or untick it to leave the record alone.");
        }
        Object parsed = parse(spec, t);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "\"" + t + "\" is not a value " + spec.label() + " can hold.");
        }
        return parsed;
    }

    /**
     * The email's reading of one field, in the form a decision is stored as.
     *
     * <p>The value as it was <em>compared</em> — a capacity already converted to cubic metres,
     * an IMO already normalised — and not as it was printed: the printed form carries a unit
     * and is a rendering, and two renderings of one figure would be stored as two different
     * declined values, neither recognising the other.
     *
     * @return null where the email said nothing about this field
     */
    public static String incomingValue(Vessel vessel, Extraction.ExtractedVessel reading,
                                       String field) {
        Spec spec = specOf(field);
        if (spec == null) return null;
        Object incoming = spec.incoming().apply(reading, vessel);
        return isAbsent(incoming) ? null : canonical(incoming);
    }

    /**
     * Whether a value settled earlier is the one this email is reporting now.
     *
     * <p>Compared with {@link #same}, not as text: the stored value is read back into the
     * field's own type first, so a deadweight a broker rounds differently on Wednesday is still
     * the figure that was turned down on Tuesday. A queue that fires on a re-rounded number is
     * the queue this whole mechanism exists to stop.
     */
    public static boolean reportsValue(Vessel vessel, Extraction.ExtractedVessel reading,
                                       String field, String settledValue) {
        Spec spec = specOf(field);
        if (spec == null || settledValue == null) return false;
        Object incoming = spec.incoming().apply(reading, vessel);
        if (isAbsent(incoming)) return false;
        Object settled = parse(spec, settledValue);
        return settled != null && same(settled, incoming);
    }

    /**
     * Every field this reading reports, canonically — what {@code vessel_field_reports} keeps.
     *
     * <p>Agreeing or not, because the history is worth most exactly where the record is right:
     * three firms repeating what is on file is what makes a fourth firm's different figure a
     * minor question rather than a correction.
     */
    public static Map<String, String> reportedValues(Vessel vessel, Extraction.ExtractedVessel reading) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Spec spec : SPECS) {
            Object incoming = spec.incoming().apply(reading, vessel);
            if (!isAbsent(incoming)) out.put(spec.field(), canonical(incoming));
        }
        return out;
    }

    /** What her record holds for a field, canonically, or null where it holds nothing. */
    public static String currentValue(Vessel vessel, String field) {
        Spec spec = specOf(field);
        if (spec == null) return null;
        Object current = spec.current().apply(vessel);
        return isAbsent(current) ? null : canonical(current);
    }

    /**
     * Whether two stored values are one figure, with the tolerance the diff uses.
     *
     * <p>For the history and the decisions, which hold text: a deadweight re-rounded by half a
     * percent is the same statement, and a string test would count it as a second opinion.
     */
    public static boolean sameValue(String field, String a, String b) {
        if (a == null || b == null) return false;
        Spec spec = specOf(field);
        if (spec == null) return a.equals(b);
        Object x = parse(spec, a);
        Object y = parse(spec, b);
        if (x == null || y == null) return a.equals(b);
        return same(x, y);
    }

    /**
     * Below this, a disagreement is a broker's rounding rather than a different ship.
     *
     * <p>Relative, per field, and deliberately wider than the half percent at which a
     * difference is a difference at all. Between the two sits "she is 28,150 against 28,400":
     * worth keeping, not worth a morning. A draft is two per cent because 7.9 against 8.0 is a
     * summer-against-tropical reading, not a new hull; capacities three because grain is quoted
     * off plans and bale off whichever plan was nearer.
     */
    private static final Map<String, BigDecimal> SMALL_RELATIVE = Map.of(
            "deadweightTonnage", new BigDecimal("0.02"),
            "deadweightCargoCapacity", new BigDecimal("0.03"),
            "maximumDraft", new BigDecimal("0.02"),
            "grainCapacityM3", new BigDecimal("0.03"),
            "baleCapacityM3", new BigDecimal("0.03"));

    /**
     * Why this disagreement is too small to be a question, or null where it is not small.
     *
     * <p>A build year one apart is small too — delivery against keel-laying, the one two
     * brokers argue about for every ship built in December. Counts, flags, names and fittings
     * are never small: a hull with four holds is not nearly one with five.
     */
    public static String smallDifference(String field, String current, String incoming) {
        if (current == null || incoming == null) return null;
        Spec spec = specOf(field);
        if (spec == null) return null;
        Object a = parse(spec, current);
        Object b = parse(spec, incoming);
        if ("yearBuilt".equals(field) && a instanceof Integer x && b instanceof Integer y) {
            return Math.abs(x - y) <= 1 ? "A year apart - delivery against keel-laying" : null;
        }
        BigDecimal limit = SMALL_RELATIVE.get(field);
        if (limit == null || !(a instanceof BigDecimal x) || !(b instanceof BigDecimal y)) return null;
        BigDecimal larger = x.abs().max(y.abs());
        if (larger.signum() == 0) return null;
        BigDecimal share = x.subtract(y).abs().divide(larger, new MathContext(9, RoundingMode.HALF_UP));
        if (share.compareTo(limit) > 0) return null;
        return "Within " + limit.movePointRight(2).stripTrailingZeros().toPlainString()
                + "% of the record (" + share.movePointRight(2).setScale(1, RoundingMode.HALF_UP)
                .toPlainString() + "%)";
    }

    /** Whether a field says which hull she is, rather than what she is like. */
    public static boolean isIdentity(String field) {
        return "name".equals(field) || "imoNumber".equals(field);
    }

    /** How a value is stored and handed back, with no unit on it. */
    public static String canonical(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
        if (value instanceof Boolean b) return b.toString();
        return value.toString().trim();
    }

    /** Whether a field is a hold capacity — the one kind whose unit is worth arguing about. */
    public static boolean isCapacity(String field) {
        return "grainCapacityM3".equals(field) || "baleCapacityM3".equals(field);
    }

    // --------------------------------------------------------------- internals

    private static Spec specOf(String field) {
        return SPECS.stream().filter(s -> s.field().equals(field)).findFirst().orElse(null);
    }

    private static Object parse(Spec spec, String text) {
        String t = text.trim().replace(",", "");
        try {
            return switch (spec.kind()) {
                case TEXT -> t;
                case DECIMAL -> new BigDecimal(stripUnit(t));
                case INTEGER -> Integer.valueOf(stripUnit(t));
                case SHORT -> Short.valueOf(stripUnit(t));
                case BOOLEAN -> bool(t);
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The number out of "8240 m3".
     *
     * <p>The review screen prints every figure with its unit, and the natural way to correct one
     * is to edit what is shown. Only a trailing unit is dropped — anything else that is not a
     * number still fails, so "abt 8240" is refused rather than read as 8240 and written as
     * though somebody had meant it exactly.
     */
    private static String stripUnit(String t) {
        return t.replaceAll("(?i)\\s*(m3|m³|cbm|mt|t|m)$", "").trim();
    }

    /** "yes" and "no" as well as the words Java knows, because that is how {@link #print} writes them. */
    private static Boolean bool(String t) {
        return switch (t.toLowerCase()) {
            case "yes", "y", "true", "1" -> Boolean.TRUE;
            case "no", "n", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * A capacity in cubic metres, or null when it cannot be known.
     *
     * <p>Her own size first, then the other capacity's answer, then the label — see the class
     * comment for the list that made the old order wrong. Null means there was no size to judge
     * by, no sibling that could be judged either, and no unit stated: the one case that is not
     * guessed at.
     *
     * @param sibling the other hold capacity in the same reading, consulted only when this
     *                figure's own size cannot place it
     */
    private static BigDecimal cubicMetres(BigDecimal value, BigDecimal sibling,
                                          Extraction.ExtractedVessel p, Vessel vessel) {
        if (value == null || value.signum() <= 0) return null;
        // The email's deadweight where it gives one, else hers: a list repeating a ship's
        // capacities often leaves out the size the record already holds.
        BigDecimal dwt = positive(p.dwt()) != null ? p.dwt() : vessel.getDeadweightTonnage();
        BigDecimal dwcc = positive(p.dwcc()) != null ? p.dwcc() : vessel.getDeadweightCargoCapacity();

        CapacityUnits.Unit unit = CapacityUnits.bySize(value, dwt, dwcc);
        if (unit == null) unit = CapacityUnits.bySize(sibling, dwt, dwcc);
        if (unit == null) unit = CapacityUnits.stated(p.capacityUnit());
        return CapacityUnits.toCubicMetres(value, unit);
    }

    private static BigDecimal positive(BigDecimal d) {
        return d == null || d.signum() <= 0 ? null : d;
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
