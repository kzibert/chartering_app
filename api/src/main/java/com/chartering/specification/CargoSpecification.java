package com.chartering.specification;

import com.chartering.model.Cargo;
import com.chartering.model.SourceKind;
import com.chartering.model.CargoStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
     * Cargoes loading somewhere whose name contains the text, however the place was recorded.
     *
     * <p>Every one of the three ways a place is held is searched, and both areas: the port on
     * file, the words the email used, the area typed by hand and the area the port sits in. A
     * box labelled "Load" that found "Chornomorsk" but not a cargo entered as "Chornomorsk
     * port" in free text - or found "Black Sea" only where nobody had picked a port - would be
     * a box that answers a question about how the row was typed.
     */
    public static Specification<Cargo> loadPlaceContains(String text) {
        return placeContains("loadPort", "loadPortText", "loadArea", text);
    }

    public static Specification<Cargo> dischargePlaceContains(String text) {
        return placeContains("dischargePort", "dischargePortText", "dischargeArea", text);
    }

    private static Specification<Cargo> placeContains(String portAttr, String textAttr,
                                                      String areaAttr, String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) return null;
            String like = "%" + text.trim().toLowerCase() + "%";
            Join<?, ?> port = root.join(portAttr, JoinType.LEFT);
            Join<?, ?> portArea = port.join("tradeArea", JoinType.LEFT);
            Join<?, ?> area = root.join(areaAttr, JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(port.get("name")), like),
                    cb.like(cb.lower(root.get(textAttr)), like),
                    cb.like(cb.lower(area.get("name")), like),
                    cb.like(cb.lower(area.get("code")), like),
                    cb.like(cb.lower(portArea.get("name")), like),
                    cb.like(cb.lower(portArea.get("code")), like));
        };
    }

    /**
     * Cargoes that last reached the desk on or after this day — "what has been sent this week".
     * Reads the same derived date the list prints, so the filter and the column cannot disagree.
     */
    public static Specification<Cargo> lastSentSince(LocalDate since) {
        return (root, query, cb) -> since == null ? null
                : cb.greaterThanOrEqualTo(root.<OffsetDateTime>get("lastSentAt"),
                        since.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime());
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
    public static Specification<Cargo> sourceKindEquals(SourceKind kind) {
        return (root, query, cb) -> kind == null ? null
                : cb.equal(root.get("sourceKind"), kind);
    }

    /**
     * Cargoes whose total commission falls in the range, and optionally those with no figure.
     *
     * <p>The range is inclusive at both ends and either end may be left out: "3 to 4 per cent"
     * is the usual question, and "at least 5" or "at most 2.5" are the others.
     * Reads {@code commissionPct}, the figure read out of the broker's words - a cargo that
     * says "3.75 pct ttl" is in the range 3 to 4, whatever the words were.
     *
     * <p>"Not stated" is opt-in, and the reason is what it does to the answer. Most first
     * emails leave the commission out, so a cargo with no figure is the common case, not an
     * edge. Sweeping every one of them into every range would make the range mean very little,
     * since most of what came back would be cargoes whose terms are still to be asked. Never
     * offering them would hide enquiries whose commission is simply not yet known, which is the
     * other failure. So a person asks for them when they want them: a range alone answers "what
     * has a stated commission in this band", and the same range with not-stated ticked answers
     * "and what has not yet said".
     *
     * <p>Nothing set means no restriction at all, so the filter contributes nothing to a list
     * that did not ask for it.
     */
    public static Specification<Cargo> commission(BigDecimal min, BigDecimal max, Boolean notStated) {
        return (root, query, cb) -> {
            boolean ranged = min != null || max != null;
            boolean includeNotStated = Boolean.TRUE.equals(notStated);
            if (!ranged && !includeNotStated) return null;
            Predicate notStatedPredicate = cb.isNull(root.get("commissionPct"));
            if (!ranged) return notStatedPredicate;
            List<Predicate> within = new ArrayList<>();
            if (min != null) {
                within.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("commissionPct"), min));
            }
            if (max != null) {
                within.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("commissionPct"), max));
            }
            Predicate inRange = cb.and(within.toArray(Predicate[]::new));
            return includeNotStated ? cb.or(inRange, notStatedPredicate) : inRange;
        };
    }
}
