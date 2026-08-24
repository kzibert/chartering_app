package com.chartering.audit;

/**
 * One row on its way into {@code data_changes}, before the change set, timestamp and user
 * are stamped on it — those are the same for every row of a transaction and belong to
 * {@link ChangeContext}, not to the individual change.
 *
 * @param fieldName the changed property, or null when this row is a whole-record create or
 *                  delete and the values are JSON snapshots
 */
record ChangeRow(
        String entityType,
        Long entityId,
        String entityLabel,
        String operation,
        String fieldName,
        String oldValue,
        String newValue) {

    static final String CREATE = "create";
    static final String UPDATE = "update";
    static final String DELETE = "delete";
}
