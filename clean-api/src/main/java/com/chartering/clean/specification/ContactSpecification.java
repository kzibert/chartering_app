package com.chartering.clean.specification;

import com.chartering.clean.model.Contact;
import org.springframework.data.jpa.domain.Specification;

public final class ContactSpecification {

    private ContactSpecification() {
    }

    public static Specification<Contact> kindEquals(String kind) {
        return (root, query, cb) -> kind == null || kind.isBlank() ? null
                : cb.equal(root.get("contactKind"), kind);
    }

    public static Specification<Contact> valueContains(String value) {
        return (root, query, cb) -> value == null || value.isBlank() ? null
                : cb.like(cb.lower(root.get("contactValue")), "%" + value.toLowerCase() + "%");
    }

    public static Specification<Contact> companyIdEquals(Long companyId) {
        return (root, query, cb) -> companyId == null ? null
                : cb.equal(root.get("company").get("id"), companyId);
    }

    public static Specification<Contact> confirmedEquals(Boolean confirmed) {
        return (root, query, cb) -> confirmed == null ? null
                : cb.equal(root.get("confirmed"), confirmed);
    }
}
