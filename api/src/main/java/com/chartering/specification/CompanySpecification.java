package com.chartering.specification;

import com.chartering.model.Company;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

public final class CompanySpecification {

    private CompanySpecification() {
    }

    public static Specification<Company> nameContains(String name) {
        return (root, query, cb) -> name == null || name.isBlank() ? null
                : cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase() + "%");
    }

    public static Specification<Company> cityContains(String city) {
        return (root, query, cb) -> city == null || city.isBlank() ? null
                : cb.like(cb.lower(root.get("cityName")), "%" + city.toLowerCase() + "%");
    }

    public static Specification<Company> roleIsTrue(String roleField, Boolean value) {
        return (root, query, cb) -> value == null || !value ? null
                : cb.isTrue(root.get(roleField));
    }

    public static Specification<Company> confirmedEquals(Boolean confirmed) {
        return (root, query, cb) -> confirmed == null ? null
                : cb.equal(root.get("confirmed"), confirmed);
    }

    /** Exclude Russian-rooted (banned) companies unless explicitly included. */
    public static Specification<Company> excludeBanned(boolean includeBanned) {
        return (root, query, cb) -> includeBanned ? null : cb.isFalse(root.get("banned"));
    }

    /** Filter by provenance: true = imported legacy, false = created in-app; null = all. */
    public static Specification<Company> legacyEquals(Boolean legacy) {
        return (root, query, cb) -> legacy == null ? null : cb.equal(root.get("legacy"), legacy);
    }

    public static Specification<Company> hasRegionId(Long regionId) {
        return (root, query, cb) -> {
            if (regionId == null) return null;
            query.distinct(true);
            return cb.equal(root.join("ports", JoinType.INNER)
                    .join("region", JoinType.INNER).get("id"), regionId);
        };
    }

    public static Specification<Company> hasPortId(Long portId) {
        return (root, query, cb) -> {
            if (portId == null) return null;
            query.distinct(true);
            return cb.equal(root.join("ports", JoinType.INNER).get("id"), portId);
        };
    }

    public static Specification<Company> hasTonnageCategoryId(Long tonnageId) {
        return (root, query, cb) -> {
            if (tonnageId == null) return null;
            query.distinct(true);
            return cb.equal(root.join("tonnageCategories", JoinType.INNER).get("id"), tonnageId);
        };
    }
}
