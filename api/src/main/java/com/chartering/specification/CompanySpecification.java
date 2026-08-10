package com.chartering.specification;

import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.Person;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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

    /**
     * Companies employing someone whose name matches. Uses an EXISTS subquery rather than a
     * join so the row count stays one-per-company (no distinct needed for paging).
     */
    public static Specification<Company> hasPersonNamed(String personName) {
        return (root, query, cb) -> {
            if (personName == null || personName.isBlank()) return null;
            String pattern = "%" + personName.toLowerCase() + "%";
            Subquery<Long> sub = query.subquery(Long.class);
            Root<Person> person = sub.from(Person.class);
            return cb.exists(sub.select(person.get("id")).where(
                    cb.equal(person.get("company").get("id"), root.get("id")),
                    cb.or(cb.like(cb.lower(person.get("fullName")), pattern),
                            cb.like(cb.lower(person.get("greetingName")), pattern))));
        };
    }

    /**
     * Companies whose email addresses are all flagged not working. A company with no email
     * at all does not match — that is a gap in the data, not a set of dead addresses.
     * Passing false inverts it (companies that still have at least one working email).
     */
    public static Specification<Company> noWorkingEmail(Boolean noWorkingEmail) {
        return (root, query, cb) -> {
            if (noWorkingEmail == null) return null;

            Subquery<Long> anyEmail = query.subquery(Long.class);
            Root<Contact> e = anyEmail.from(Contact.class);
            anyEmail.select(e.get("id")).where(
                    cb.equal(e.get("company").get("id"), root.get("id")),
                    cb.equal(e.get("contactKind"), "email"));

            Subquery<Long> workingEmail = query.subquery(Long.class);
            Root<Contact> w = workingEmail.from(Contact.class);
            workingEmail.select(w.get("id")).where(
                    cb.equal(w.get("company").get("id"), root.get("id")),
                    cb.equal(w.get("contactKind"), "email"),
                    cb.isTrue(w.get("working")));

            return noWorkingEmail
                    ? cb.and(cb.exists(anyEmail), cb.not(cb.exists(workingEmail)))
                    : cb.exists(workingEmail);
        };
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
