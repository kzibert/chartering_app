package com.chartering.specification;

import com.chartering.model.Vessel;
import jakarta.persistence.criteria.JoinType;
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

    public static Specification<Vessel> nameContains(String name) {
        return (root, query, cb) -> name == null || name.isBlank() ? null
                : cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase() + "%");
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

    public static Specification<Vessel> ownerIdEquals(Long ownerId) {
        return (root, query, cb) -> ownerId == null ? null
                : cb.equal(root.get("owner").get("id"), ownerId);
    }

    public static Specification<Vessel> ownerNameContains(String ownerName) {
        return (root, query, cb) -> {
            if (ownerName == null || ownerName.isBlank()) return null;
            return cb.like(cb.lower(root.join("owner", JoinType.LEFT).get("name")),
                    "%" + ownerName.toLowerCase() + "%");
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

    public static Specification<Vessel> numberRange(String field, BigDecimal min, BigDecimal max) {
        return (root, query, cb) -> {
            if (min == null && max == null) return null;
            if (min != null && max != null) return cb.between(root.get(field), min, max);
            if (min != null) return cb.greaterThanOrEqualTo(root.get(field), min);
            return cb.lessThanOrEqualTo(root.get(field), max);
        };
    }

    public static Specification<Vessel> yearRange(Integer min, Integer max) {
        return (root, query, cb) -> {
            if (min == null && max == null) return null;
            if (min != null && max != null) return cb.between(root.get("yearBuilt"), min, max);
            if (min != null) return cb.greaterThanOrEqualTo(root.get("yearBuilt"), min);
            return cb.lessThanOrEqualTo(root.get("yearBuilt"), max);
        };
    }
}
