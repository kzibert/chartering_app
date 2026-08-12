package com.chartering.specification;

import com.chartering.model.Contact;
import com.chartering.model.Person;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class PersonSpecification {

    private PersonSpecification() {
    }

    public static Specification<Person> companyIdEquals(Long companyId) {
        return (root, query, cb) -> companyId == null ? null
                : cb.equal(root.get("company").get("id"), companyId);
    }

    /**
     * Matches the full name or the greeting name, so searching "sergey" finds someone
     * filed as "Sergei Ivanov" with greeting name "Sergey" either way round. Mirrors the
     * person search already offered on the company list.
     */
    public static Specification<Person> nameContains(String name) {
        return (root, query, cb) -> {
            if (name == null || name.isBlank()) return null;
            String pattern = "%" + name.toLowerCase() + "%";
            return cb.or(cb.like(cb.lower(root.get("fullName")), pattern),
                    cb.like(cb.lower(root.get("greetingName")), pattern));
        };
    }

    /**
     * People who have a contact matching <em>all</em> of the given criteria at once.
     *
     * One EXISTS holding every predicate, deliberately: with a subquery per criterion,
     * kind=email + confirmed=true would match someone with an unconfirmed email and a
     * confirmed phone. Here it means what it reads like — "has a confirmed email".
     *
     * Banned contacts are excluded unless asked for, matching the contact search, so a
     * person reachable only through a banned address stays hidden by default.
     * All-null criteria means no constraint at all (people without contacts still match).
     */
    public static Specification<Person> hasContactMatching(String value, String kind,
                                                           Boolean confirmed, boolean includeBanned,
                                                           Boolean legacy) {
        boolean unconstrained = (value == null || value.isBlank())
                && (kind == null || kind.isBlank())
                && confirmed == null
                && legacy == null;

        return (root, query, cb) -> {
            if (unconstrained) return null;

            Subquery<Long> sub = query.subquery(Long.class);
            Root<Contact> ct = sub.from(Contact.class);

            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(ct.get("person").get("id"), root.get("id")));
            if (value != null && !value.isBlank()) {
                where.add(cb.like(cb.lower(ct.get("contactValue")), "%" + value.toLowerCase() + "%"));
            }
            if (kind != null && !kind.isBlank()) where.add(cb.equal(ct.get("contactKind"), kind));
            if (confirmed != null) where.add(cb.equal(ct.get("confirmed"), confirmed));
            if (legacy != null) where.add(cb.equal(ct.get("legacy"), legacy));
            if (!includeBanned) where.add(cb.isFalse(ct.get("banned")));

            return cb.exists(sub.select(ct.get("id")).where(where.toArray(Predicate[]::new)));
        };
    }
}
