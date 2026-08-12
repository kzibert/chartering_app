package com.chartering.specification;

import com.chartering.model.Person;
import org.springframework.data.jpa.domain.Specification;

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
}
