package com.chartering.specification;

import com.chartering.model.Vessel;
import com.chartering.model.VesselCompanyLink;
import com.chartering.model.VesselExName;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;

/**
 * Null-safe, composable filters for vessel search. Each method returns null when its
 * argument is absent so it contributes nothing to the combined predicate.
 */
public final class VesselSpecification {

    private VesselSpecification() {
    }

    /**
     * By name — the one she carries now <em>or</em> any she used to.
     *
     * <p>Searching former names is the entire reason they were extracted. A position list
     * arriving on Monday may use a name this database has never seen for a hull it has held
     * for ten years, and the IMO number, which is the only identifier that never moves, is
     * exactly what a broker's circular leaves out. Before this, "AMIKO" found LOIRE RIVER
     * only by the accident of her old name still being glued onto the front of her new one.
     *
     * <p>EXISTS rather than a join, so a vessel with three former names is still one row.
     */
    public static Specification<Vessel> nameContains(String name) {
        return (root, query, cb) -> {
            if (name == null || name.isBlank()) return null;
            String pattern = "%" + name.toLowerCase() + "%";
            Subquery<Long> ex = query.subquery(Long.class);
            Root<VesselExName> e = ex.from(VesselExName.class);
            return cb.or(
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.exists(ex.select(e.get("id")).where(
                            cb.equal(e.get("vessel").get("id"), root.get("id")),
                            cb.like(cb.lower(e.get("name")), pattern))));
        };
    }

    /**
     * Geared, gearless, or unknown.
     *
     * <p>Asking for geared returns only vessels a list has actually said are geared. The
     * ones with nothing on file do not come back, for the same reason a size range excludes
     * an unrecorded figure: "not known to be gearless" is not an answer a charterer accepts.
     */
    public static Specification<Vessel> gearedEquals(Boolean geared) {
        return (root, query, cb) -> geared == null ? null : cb.equal(root.get("geared"), geared);
    }

    public static Specification<Vessel> imoEquals(String imo) {
        return (root, query, cb) -> imo == null || imo.isBlank() ? null
                : cb.equal(root.get("imoNumber"), imo);
    }

    public static Specification<Vessel> vesselTypeIn(List<String> types) {
        return (root, query, cb) -> types == null || types.isEmpty() ? null
                : root.get("vesselType").in(types);
    }

    public static Specification<Vessel> flagIn(List<String> flags) {
        return (root, query, cb) -> flags == null || flags.isEmpty() ? null
                : root.get("flag").in(flags);
    }

    /**
     * Vessels this company is attached to in <em>any</em> capacity — owner or broker.
     * EXISTS rather than a join so a vessel is not duplicated when several roles match.
     */
    public static Specification<Vessel> companyIdEquals(Long companyId) {
        return (root, query, cb) -> {
            if (companyId == null) return null;
            Subquery<Long> broker = query.subquery(Long.class);
            Root<VesselCompanyLink> l = broker.from(VesselCompanyLink.class);
            return cb.or(
                    cb.equal(root.get("owner").get("id"), companyId),
                    cb.exists(broker.select(l.get("id")).where(
                            cb.equal(l.get("vessel").get("id"), root.get("id")),
                            cb.equal(l.get("company").get("id"), companyId))));
        };
    }

    /** Same, by company name substring. */
    public static Specification<Vessel> companyNameContains(String companyName) {
        return (root, query, cb) -> {
            if (companyName == null || companyName.isBlank()) return null;
            String pattern = "%" + companyName.toLowerCase() + "%";
            Subquery<Long> broker = query.subquery(Long.class);
            Root<VesselCompanyLink> l = broker.from(VesselCompanyLink.class);
            return cb.or(
                    cb.like(cb.lower(root.join("owner", JoinType.LEFT).get("name")), pattern),
                    cb.exists(broker.select(l.get("id")).where(
                            cb.equal(l.get("vessel").get("id"), root.get("id")),
                            cb.like(cb.lower(l.get("company").get("name")), pattern))));
        };
    }

    public static Specification<Vessel> confirmedEquals(Boolean confirmed) {
        return (root, query, cb) -> confirmed == null ? null
                : cb.equal(root.get("confirmed"), confirmed);
    }

    /** Exclude Russian-rooted (banned) vessels unless explicitly included. */
    public static Specification<Vessel> excludeBanned(boolean includeBanned) {
        return (root, query, cb) -> includeBanned ? null : cb.isFalse(root.get("banned"));
    }

    /** Filter by provenance: true = imported legacy, false = created in-app; null = all. */
    public static Specification<Vessel> legacyEquals(Boolean legacy) {
        return (root, query, cb) -> legacy == null ? null : cb.equal(root.get("legacy"), legacy);
    }

    /**
     * A numeric range that only matches vessels where the figure is actually on file.
     *
     * <p>These columns have no NULLs: an unrecorded measurement is stored as 0. A plain
     * {@code <= max} would therefore match every vessel whose figure is simply unknown —
     * 2355 of 4566 have no DWCC — and quietly return a list that looks filtered but is not.
     * Requiring {@code field > 0} makes "under 50 000 t" mean vessels known to be under it,
     * rather than vessels not known to be over it.
     *
     * @return null when neither bound is set, so the caller can drop the criterion
     */
    public static Specification<Vessel> recordedRange(String field, BigDecimal min, BigDecimal max) {
        return (root, query, cb) -> {
            if (min == null && max == null) return null;
            Predicate recorded = cb.greaterThan(root.get(field), BigDecimal.ZERO);
            if (min != null && max != null) {
                return cb.and(recorded, cb.between(root.get(field), min, max));
            }
            return cb.and(recorded, min != null
                    ? cb.greaterThanOrEqualTo(root.get(field), min)
                    : cb.lessThanOrEqualTo(root.get(field), max));
        };
    }

    /**
     * Vessels built in {@code year} or later — "the oldest I will accept".
     *
     * <p>Vessels with no year on file (105 of them, stored as NULL or 0) do not match: we
     * cannot confirm they are young enough, and silently including them would defeat the
     * point of asking. NULL falls out of the comparison on its own; 0 is excluded by it.
     */
    public static Specification<Vessel> yearFrom(Integer year) {
        return (root, query, cb) ->
                year == null ? null : cb.greaterThanOrEqualTo(root.get("yearBuilt"), year);
    }
}
