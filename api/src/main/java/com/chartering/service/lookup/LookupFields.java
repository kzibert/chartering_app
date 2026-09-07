package com.chartering.service.lookup;

import com.chartering.model.Vessel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * What an outside source is allowed to write to a hull, and what it is not.
 *
 * <p><b>Five fields, and the list is short on purpose.</b> These are the ones a public ship
 * database states about the ship herself and states in a form worth trusting. Everything a
 * charterer actually asks about — draft, capacities, gear, holds, fittings — is absent,
 * because the sources either do not carry it or carry something that looks like it and is
 * not. The standing example is draught: a tracking page shows the AIS-reported
 * <em>current</em> draught, which is how deep she floats today with cargo in her, and the
 * column it would land in is a design maximum. That substitution would be invisible and would
 * be wrong in the direction that loses cargoes.
 *
 * <p><b>The IMO is the field this whole feature exists for.</b> It is the only identifier
 * that survives a rename, it is exactly what a broker's circular leaves out, and once it is
 * on the record every future position list finds her without any of this.
 *
 * <p>Nothing here decides. Each field is proposed with the value, the source page and the
 * confidence behind it, and a person ticks the ones they believe.
 */
public final class LookupFields {

    private LookupFields() {
    }

    private record Spec(String field,
                        String label,
                        String unit,
                        Function<Vessel, Object> current,
                        Function<VesselParticulars, Object> incoming,
                        BiConsumer<Vessel, Object> write) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("imoNumber", "IMO", null,
                    Vessel::getImoNumber, VesselParticulars::imo,
                    (v, o) -> v.setImoNumber((String) o)),
            new Spec("deadweightTonnage", "DWT", "t",
                    Vessel::getDeadweightTonnage, VesselParticulars::deadweightTonnage,
                    (v, o) -> v.setDeadweightTonnage((BigDecimal) o)),
            new Spec("yearBuilt", "Built", null,
                    Vessel::getYearBuilt, VesselParticulars::yearBuilt,
                    (v, o) -> v.setYearBuilt((Integer) o)),
            new Spec("flag", "Flag", null,
                    Vessel::getFlag, VesselParticulars::flag,
                    (v, o) -> v.setFlag((String) o)),
            new Spec("vesselType", "Type", null,
                    Vessel::getVesselType, VesselParticulars::vesselType,
                    (v, o) -> v.setVesselType((String) o)));

    /**
     * One field the source can supply, beside what the record holds.
     *
     * @param differs true when the record already holds something else. Those are the ones
     *                worth a second look: filling a blank from a public database is ordinary,
     *                overwriting a figure a broker checked with one is not
     */
    public record Proposal(String field, String label, String current, String incoming,
                           boolean differs) {
    }

    /** What the source could add to this hull, empty fields and disagreements alike. */
    public static List<Proposal> proposals(Vessel vessel, VesselParticulars candidate) {
        List<Proposal> out = new ArrayList<>();
        for (Spec spec : SPECS) {
            Object incoming = spec.incoming().apply(candidate);
            if (isAbsent(incoming)) continue;
            Object current = vessel == null ? null : spec.current().apply(vessel);
            boolean differs = !isAbsent(current) && !same(current, incoming);
            out.add(new Proposal(spec.field(), spec.label(),
                    print(current, spec.unit()), print(incoming, spec.unit()), differs));
        }
        return List.copyOf(out);
    }

    /**
     * Write the fields a person ticked, and only those.
     *
     * @return the fields actually changed, for the line the screen shows back
     */
    public static List<String> apply(Vessel vessel, VesselParticulars candidate,
                                     Collection<String> fields) {
        List<String> written = new ArrayList<>();
        for (Spec spec : SPECS) {
            if (!fields.contains(spec.field())) continue;
            Object incoming = spec.incoming().apply(candidate);
            if (isAbsent(incoming)) continue;
            if (same(spec.current().apply(vessel), incoming)) continue;
            spec.write().accept(vessel, incoming);
            written.add(spec.field());
        }
        return written;
    }

    public static String labelOf(String field) {
        return SPECS.stream().filter(s -> s.field().equals(field))
                .map(Spec::label).findFirst().orElse(field);
    }

    /** Every field this can write, for a request to be checked against. */
    public static Map<String, String> fields() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Spec s : SPECS) out.put(s.field(), s.label());
        return out;
    }

    /** Blank, null, or the zero the older rows here use to mean "not on file". */
    private static boolean isAbsent(Object value) {
        if (value == null) return true;
        if (value instanceof String s) return s.isBlank();
        if (value instanceof BigDecimal d) return d.signum() == 0;
        if (value instanceof Number n) return n.longValue() == 0;
        return false;
    }

    private static boolean same(Object current, Object incoming) {
        if (isAbsent(current)) return false;
        if (current instanceof BigDecimal a && incoming instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        if (current instanceof Number a && incoming instanceof Number b) {
            return a.longValue() == b.longValue();
        }
        if (current instanceof String a && incoming instanceof String b) {
            return a.trim().equalsIgnoreCase(b.trim());
        }
        return current.equals(incoming);
    }

    private static String print(Object value, String unit) {
        if (isAbsent(value)) return null;
        String text = value instanceof BigDecimal d
                ? d.stripTrailingZeros().toPlainString() : String.valueOf(value);
        return unit == null ? text : text + " " + unit;
    }
}
