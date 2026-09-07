package com.chartering.service.parser;

import com.chartering.model.Cargo;
import com.chartering.service.QuantityTolerance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Folding a second sighting of a cargo into the one already on file.
 *
 * <p><b>Gap-fill only, and never an overwrite.</b> This is the contacts importer's rule and
 * it is more clearly right here than anywhere else it is applied: the two readings are two
 * brokers describing one enquiry, and neither of them is the charterer. Where the record is
 * silent and the new email speaks, the record learns something. Where they disagree — one
 * says 25,000 and the other 26,000 — nothing is written, because there is no reason to
 * believe the second email over the first, and the second one arriving later is not one.
 *
 * <p>So the disagreements are <em>reported and left alone</em>. They go on the merge
 * proposal as "these differ; the cargo keeps what it has", which is the honest thing to show
 * and is what tells a broker the two firms are quoting different numbers — itself worth
 * knowing, and invisible if one had silently won.
 *
 * <p><b>This is why the vessel side has field-level acceptance and this side does not.</b> A
 * vessel's particulars are a fact about a hull that one of the two readings has right, so
 * picking is the whole job. A cargo's are a negotiating position, and a merge is about
 * keeping one row rather than about deciding whose figures are true — the differing values
 * stay visible on the sources, and the broker edits the cargo by hand if they want one.
 */
public final class CargoFieldDiff {

    private CargoFieldDiff() {
    }

    /** What a merge would do, and what it deliberately would not. */
    public record Result(List<String> filled, List<FieldDiff> differing) {
    }

    /**
     * Fill the gaps in {@code existing} from a second reading.
     *
     * @param apply false to report without writing, which is what building the proposal does
     */
    public static Result merge(Cargo existing, ResolvedCargo resolved, boolean apply) {
        Collector c = new Collector(apply);
        Extraction.ExtractedCargo p = resolved.parsed();

        c.field("quantity", "Quantity", null,
                existing::getQuantity, p.quantity(), existing::setQuantity);
        c.field("quantityTolerance", "Tolerance", null,
                existing::getQuantityTolerance, Extraction.text(p.quantityTolerance()),
                existing::setQuantityTolerance);
        c.field("stowageFactor", "Stowage factor", null,
                existing::getStowageFactor, p.stowageFactor(), existing::setStowageFactor);

        c.field("loadPort", "Load port", null,
                existing::getLoadPort, resolved.loadPort(), existing::setLoadPort);
        c.field("loadPortText", "Load port (as written)", null,
                existing::getLoadPortText, Extraction.text(p.loadPort()), existing::setLoadPortText);
        c.field("loadArea", "Load area", null,
                existing::getLoadArea, resolved.loadArea(), existing::setLoadArea);
        c.field("dischargePort", "Discharge port", null,
                existing::getDischargePort, resolved.dischargePort(), existing::setDischargePort);
        c.field("dischargePortText", "Discharge port (as written)", null,
                existing::getDischargePortText, Extraction.text(p.dischargePort()),
                existing::setDischargePortText);
        c.field("dischargeArea", "Discharge area", null,
                existing::getDischargeArea, resolved.dischargeArea(), existing::setDischargeArea);

        c.field("laycanFrom", "Laycan from", null,
                existing::getLaycanFrom, resolved.laycanFrom(), existing::setLaycanFrom);
        c.field("laycanTo", "Laycan to", null,
                existing::getLaycanTo, resolved.laycanTo(), existing::setLaycanTo);
        c.field("laycanText", "Laycan (as written)", null,
                existing::getLaycanText, Extraction.text(p.laycanText()), existing::setLaycanText);

        c.field("minDwt", "Min DWT", "t", existing::getMinDwt, p.minDwt(), existing::setMinDwt);
        c.field("maxDwt", "Max DWT", "t", existing::getMaxDwt, p.maxDwt(), existing::setMaxDwt);
        c.field("maxDraft", "Max draft", "m",
                existing::getMaxDraft, p.maxDraft(), existing::setMaxDraft);
        c.field("maxAgeYears", "Max age", "years",
                existing::getMaxAgeYears, shortOf(p.maxAgeYears()), existing::setMaxAgeYears);
        c.field("requiresGeared", "Needs gear", null,
                existing::getRequiresGeared, p.requiresGeared(), existing::setRequiresGeared);
        c.field("requiresGrainFitted", "Needs grain fitted", null,
                existing::getRequiresGrainFitted, p.requiresGrainFitted(),
                existing::setRequiresGrainFitted);
        c.field("requiresImoFitted", "Needs IMO fitted", null,
                existing::getRequiresImoFitted, p.requiresImoFitted(), existing::setRequiresImoFitted);

        c.field("freightIdea", "Freight idea", null,
                existing::getFreightIdea, Extraction.text(p.freightIdea()), existing::setFreightIdea);
        c.field("commission", "Commission", null,
                existing::getCommission, Extraction.text(p.commission()), existing::setCommission);
        c.field("terms", "Terms", null,
                existing::getTerms, Extraction.text(p.terms()), existing::setTerms);
        c.field("loadRate", "Load rate", null,
                existing::getLoadRate, Extraction.text(p.loadRate()), existing::setLoadRate);
        c.field("dischargeRate", "Discharge rate", null,
                existing::getDischargeRate, Extraction.text(p.dischargeRate()),
                existing::setDischargeRate);
        c.field("chartererCompany", "Charterer", null,
                existing::getChartererCompany, resolved.charterer(), existing::setChartererCompany);

        // The comparison range is derived rather than filled, and only when the merge
        // actually wrote a quantity or a tolerance - it is arithmetic over two other columns,
        // so copying it as a field of its own would let it disagree with them.
        if (apply && (c.filled.contains("quantity") || c.filled.contains("quantityTolerance"))
                && existing.getQuantityMin() == null && existing.getQuantityMax() == null) {
            QuantityTolerance.rangeOf(existing.getQuantity(), existing.getQuantityTolerance())
                    .ifPresent(r -> {
                        existing.setQuantityMin(r.min());
                        existing.setQuantityMax(r.max());
                    });
        }

        return new Result(List.copyOf(c.filled), List.copyOf(c.differing));
    }

    private static Short shortOf(Integer value) {
        return value == null ? null : value.shortValue();
    }

    /**
     * One pass, two lists.
     *
     * <p>A collector rather than a spec table like the vessel side's: a cargo has no
     * selective acceptance, so nothing here needs to look a field up by name later, and a
     * table of thirty {@code Function}s would be ceremony for one caller.
     */
    private static final class Collector {

        private final boolean apply;
        private final List<String> filled = new ArrayList<>();
        private final List<FieldDiff> differing = new ArrayList<>();

        private Collector(boolean apply) {
            this.apply = apply;
        }

        private <T> void field(String name, String label, String unit,
                               Supplier<T> current, T incoming, Consumer<T> write) {
            if (incoming == null || (incoming instanceof String s && s.isBlank())) return;
            T existing = current.get();
            if (existing == null || (existing instanceof String s && s.isBlank())) {
                if (apply) write.accept(incoming);
                filled.add(name);
                return;
            }
            if (!same(existing, incoming)) {
                differing.add(new FieldDiff(name, label, print(existing, unit), print(incoming, unit)));
            }
        }

        private static boolean same(Object a, Object b) {
            if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
            if (a instanceof String x && b instanceof String y) {
                return x.trim().equalsIgnoreCase(y.trim());
            }
            // Entities: two rows are the same row or they are not, and the associations here
            // are all loaded through the same session, so identity by id is the honest test.
            if (a instanceof com.chartering.model.Port x && b instanceof com.chartering.model.Port y) {
                return Objects.equals(x.getId(), y.getId());
            }
            if (a instanceof com.chartering.model.TradeArea x
                    && b instanceof com.chartering.model.TradeArea y) {
                return Objects.equals(x.getId(), y.getId());
            }
            if (a instanceof com.chartering.model.Company x
                    && b instanceof com.chartering.model.Company y) {
                return Objects.equals(x.getId(), y.getId());
            }
            return a.equals(b);
        }

        private static String print(Object value, String unit) {
            String text;
            if (value instanceof BigDecimal d) text = d.stripTrailingZeros().toPlainString();
            else if (value instanceof Boolean b) text = b ? "yes" : "no";
            else if (value instanceof com.chartering.model.Port p) text = p.getName();
            else if (value instanceof com.chartering.model.TradeArea a) text = a.getName();
            else if (value instanceof com.chartering.model.Company c) text = c.getName();
            else text = String.valueOf(value);
            return unit == null ? text : text + " " + unit;
        }
    }
}
