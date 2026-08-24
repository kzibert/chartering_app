package com.chartering.specification;

import com.chartering.model.DataChange;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class DataChangeSpecification {

    private DataChangeSpecification() {
    }

    public static Specification<DataChange> entityTypeEquals(String entityType) {
        return (root, query, cb) -> entityType == null || entityType.isBlank() ? null
                : cb.equal(root.get("entityType"), entityType);
    }

    public static Specification<DataChange> entityIdEquals(Long entityId) {
        return (root, query, cb) -> entityId == null ? null : cb.equal(root.get("entityId"), entityId);
    }

    public static Specification<DataChange> operationEquals(String operation) {
        return (root, query, cb) -> operation == null || operation.isBlank() ? null
                : cb.equal(root.get("operation"), operation);
    }

    public static Specification<DataChange> fieldEquals(String field) {
        return (root, query, cb) -> field == null || field.isBlank() ? null
                : cb.equal(root.get("fieldName"), field);
    }

    public static Specification<DataChange> changedByEquals(String user) {
        return (root, query, cb) -> user == null || user.isBlank() ? null
                : cb.equal(cb.lower(root.get("changedBy")), user.toLowerCase());
    }

    public static Specification<DataChange> changeSetEquals(UUID changeSet) {
        return (root, query, cb) -> changeSet == null ? null : cb.equal(root.get("changeSet"), changeSet);
    }

    public static Specification<DataChange> changedFrom(OffsetDateTime from) {
        return (root, query, cb) -> from == null ? null
                : cb.greaterThanOrEqualTo(root.get("changedAt"), from);
    }

    public static Specification<DataChange> changedUntil(OffsetDateTime until) {
        return (root, query, cb) -> until == null ? null
                : cb.lessThanOrEqualTo(root.get("changedAt"), until);
    }

    /**
     * Free text over the label and both values.
     *
     * <p>Searching the values as well as the label is the point of having it: "who set that
     * address to not working" and "when did this phone number appear" are both questions
     * about a value that is no longer anywhere else in the database. The snapshot JSON of a
     * create or delete is caught by the same LIKE, which is how a deleted record is found by
     * something it used to contain.
     */
    public static Specification<DataChange> textContains(String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) return null;
            String like = "%" + text.toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("entityLabel")), like),
                    cb.like(cb.lower(root.get("oldValue")), like),
                    cb.like(cb.lower(root.get("newValue")), like));
        };
    }
}
