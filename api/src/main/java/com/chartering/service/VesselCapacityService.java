package com.chartering.service;

import com.chartering.audit.ChangeContext;
import com.chartering.dto.VesselCapacityCheckResponse;
import com.chartering.dto.VesselCapacityCheckResponse.Conversion;
import com.chartering.dto.VesselCapacityCheckResponse.Implausible;
import com.chartering.model.Vessel;
import com.chartering.repository.VesselRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The fleet's grain and bale figures checked against each ship's size, and the ones in cubic
 * feet put back into cubic metres.
 *
 * <p>The columns have always been cubic metres and most of the fleet is, but figures copied
 * from circulars in cubic feet sat among them thirty-five times too large — a 38,000-tonner
 * "holding" 1.6 million m³ — and Match's cubic check read them as they stood. The rule is
 * {@link CapacityUnits#bySize}: a figure plausible only as cubic feet is converted, and one that
 * fits neither unit is reported and left for a person, since no rule says what it was meant to
 * be.
 *
 * <p><b>One named change set, written through the entities.</b> Every conversion is an ordinary
 * field update the audit listener records, all under {@link #CHANGE_SET}, so the History tab
 * shows the operation as one event and any single figure can be reverted from there. Run twice,
 * it converts nothing the second time: a converted figure is plausible in cubic metres.
 */
@Service
@RequiredArgsConstructor
public class VesselCapacityService {

    public static final String CHANGE_SET = "Capacity units: cubic feet recalculated to m³ by vessel size";

    private final VesselRepository vessels;

    /** What a figure on one vessel turned out to be. */
    record Finding(String field, BigDecimal value, BigDecimal deadweight, BigDecimal perTonne,
                   BigDecimal cubicMetres) {

        boolean isCubicFeet() {
            return cubicMetres != null;
        }
    }

    private record Column(String field, Function<Vessel, BigDecimal> read, BiConsumer<Vessel, BigDecimal> write) {
    }

    private static final List<Column> COLUMNS = List.of(
            new Column("grainCapacityM3", Vessel::getGrainCapacityM3, Vessel::setGrainCapacityM3),
            new Column("baleCapacityM3", Vessel::getBaleCapacityM3, Vessel::setBaleCapacityM3));

    @Transactional(readOnly = true)
    public VesselCapacityCheckResponse check() {
        return run(false);
    }

    @Transactional
    public VesselCapacityCheckResponse fix() {
        ChangeContext.describe(CHANGE_SET);
        return run(true);
    }

    private VesselCapacityCheckResponse run(boolean apply) {
        List<Conversion> converted = new ArrayList<>();
        List<Implausible> implausible = new ArrayList<>();
        for (Vessel v : vessels.findAll(Sort.by("name"))) {
            for (Finding f : classify(v)) {
                if (f.isCubicFeet()) {
                    converted.add(new Conversion(v.getId(), v.getName(), f.field(), f.deadweight(),
                            f.value(), f.cubicMetres(), f.perTonne()));
                    if (apply) {
                        COLUMNS.stream().filter(c -> c.field().equals(f.field())).findFirst()
                                .ifPresent(c -> c.write().accept(v, f.cubicMetres()));
                    }
                } else {
                    implausible.add(new Implausible(v.getId(), v.getName(), f.field(), f.deadweight(),
                            f.value(), f.perTonne()));
                }
            }
        }
        return new VesselCapacityCheckResponse(apply, apply ? CHANGE_SET : null, converted, implausible);
    }

    /**
     * The figures on one vessel worth reporting: those that are cubic feet, and those no unit
     * explains. A figure that fits in cubic metres, a zero, and a figure on a ship with no size
     * on file say nothing and are left out.
     */
    static List<Finding> classify(Vessel v) {
        BigDecimal size = positive(v.getDeadweightTonnage()) != null
                ? v.getDeadweightTonnage() : positive(v.getDeadweightCargoCapacity());
        if (size == null) return List.of();
        List<Finding> out = new ArrayList<>();
        for (Column c : COLUMNS) {
            BigDecimal value = positive(c.read().apply(v));
            if (value == null) continue;
            BigDecimal perTonne = value.divide(size, 2, RoundingMode.HALF_UP);
            CapacityUnits.Unit unit = CapacityUnits.bySize(value, v.getDeadweightTonnage(),
                    v.getDeadweightCargoCapacity());
            if (unit == CapacityUnits.Unit.CBM) continue;
            BigDecimal m3 = unit == CapacityUnits.Unit.CBFT
                    ? CapacityUnits.toCubicMetres(value, unit).setScale(0, RoundingMode.HALF_UP)
                    : null;
            out.add(new Finding(c.field(), value, size, perTonne, m3));
        }
        return out;
    }

    private static BigDecimal positive(BigDecimal d) {
        return d == null || d.signum() <= 0 ? null : d;
    }
}
