package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry in the change log.
 *
 * <p>{@code fieldName} tells the two row shapes apart: present means one field of an update,
 * with {@code oldValue} and {@code newValue} being that field's values; absent means the
 * whole record appeared or vanished, and the values are JSON snapshots of it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DataChangeResponse(
        Long id,
        UUID changeSet,
        String entityType,
        Long entityId,
        /** what the record was called when it changed — the only name a deleted row has left */
        String entityLabel,
        String operation,
        String fieldName,
        String oldValue,
        String newValue,
        OffsetDateTime changedAt,
        String changedBy,
        String context,
        /**
         * Whether this row can be put back with one click.
         *
         * <p>Derived, not stored: it is a question about the code as it stands now, not
         * about what happened then. True only for a field update whose stored value the
         * revert knows how to convert back — see {@code DataChangeService#revert}.
         */
        boolean revertible,
        /** Why not, when it is not. Absent when it is. */
        String revertBlockedReason) {
}
