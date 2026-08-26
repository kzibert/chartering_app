package com.chartering.specification;

import com.chartering.model.Cargo;
import com.chartering.model.CargoStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Null-safe, composable filters for the Cargoes tab. Each method returns null when its
 * argument is absent, so it contributes nothing to the combined predicate.
 */
public final class CargoSpecification {

    private CargoSpecification() {
    }

    public static Specification<Cargo> commodityContains(String commodity) {
        return (root, query, cb) -> commodity == null || commodity.isBlank() ? null
                : cb.like(cb.lower(root.get("commodity")), "%" + commodity.toLowerCase() + "%");
    }

    public static Specification<Cargo> statusIn(List<CargoStatus> statuses) {
        return (root, query, cb) -> statuses == null || statuses.isEmpty() ? null
                : root.get("status").in(statuses);
    }

    /**
     * Cargoes loading in this area, counting a load port that sits in it.
     *
     * <p>Both halves are needed and neither alone is enough: a cargo entered as "Chornomorsk"
     * has a port and no area, one entered as "Spain Med" has an area and no port, and a
     * search for the Black Sea has to find the first while a search for the West Med finds
     * the second.
     *
     * <p>Exact area rather than the area and everything inside it. Widening this to
     * containment is a question for the query planner and for the UI both — a dropdown that
     * silently means "and all children" is a dropdown people stop trusting — so Match does
     * the nesting where it can explain itself, and the filter box means what it says.
     */
    public static Specification<Cargo> loadAreaEquals(Long areaId) {
        return (root, query, cb) -> {
            if (areaId == null) return null;
            return cb.or(
                    cb.equal(root.get("loadArea").get("id"), areaId),
                    cb.equal(root.join("loadPort", JoinType.LEFT).get("tradeArea").get("id"), areaId));
        };
    }

    public static Specification<Cargo> dischargeAreaEquals(Long areaId) {
        return (root, query, cb) -> {
            if (areaId == null) return null;
            return cb.or(
                    cb.equal(root.get("dischargeArea").get("id"), areaId),
                    cb.equal(root.join("dischargePort", JoinType.LEFT).get("tradeArea").get("id"), areaId));
        };
    }

    public static Specification<Cargo> loadPortEquals(Long portId) {
        return (root, query, cb) -> portId == null ? null
                : cb.equal(root.get("loadPort").get("id"), portId);
    }

    /**
     * Cargoes whose laycan overlaps the window asked about — not ones that sit inside it.
     *
     * <p>A cargo laycan 1/15 September is a cargo worth seeing when looking at the first
     * week of September, and containment would hide it. Either bound may be left out.
     *
     * <p>A cargo with no laycan on file is returned whatever the window, because "the
     * charterer has not said yet" is not the same as "not in September" and the desk still
     * has to work it.
     */
    public static Specification<Cargo> laycanOverlaps(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            if (from == null && to == null) return null;
            List<Predicate> within = new ArrayList<>();
            if (to != null) within.add(cb.lessThanOrEqualTo(root.get("laycanFrom"), to));
            if (from != null) within.add(cb.greaterThanOrEqualTo(root.get("laycanTo"), from));
            return cb.or(
                    cb.and(within.toArray(Predicate[]::new)),
                    cb.and(cb.isNull(root.get("laycanFrom")), cb.isNull(root.get("laycanTo"))));
        };
    }

    /**
     * Cargoes whose quantity range overlaps a size the caller has tonnage for.
     *
     * <p>Reads quantity_min/max, which is why the service keeps them filled: comparing a
     * hull against "25,000 +/- 10%" written as text is not something a query can do.
     */
    public static Specification<Cargo> quantityOverlaps(BigDecimal min, BigDecimal max) {
        return (root, query, cb) -> {
            if (min == null && max == null) return null;
            List<Predicate> ps = new ArrayList<>();
            if (max != null) {
                ps.add(cb.or(cb.isNull(root.get("quantityMin")),
                        cb.lessThanOrEqualTo(root.get("quantityMin"), max)));
            }
            if (min != null) {
                ps.add(cb.or(cb.isNull(root.get("quantityMax")),
                        cb.greaterThanOrEqualTo(root.get("quantityMax"), min)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    public static Specification<Cargo> companyIdEquals(Long companyId) {
        return (root, query, cb) -> companyId == null ? null
                : cb.or(cb.equal(root.get("chartererCompany").get("id"), companyId),
                        cb.equal(root.get("brokerCompany").get("id"), companyId));
    }

    /** Read out of an email, or typed. Answers the Cargoes tab's Source filter. */
    public static Specification<Cargo> fromMailEquals(Boolean fromMail) {
        return (root, query, cb) -> fromMail == null ? null
                : cb.equal(root.get("fromMail"), fromMail);
    }
}
