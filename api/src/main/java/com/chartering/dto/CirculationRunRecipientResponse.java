package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One address a run touched. {@code status} distinguishes delivered from skipped, and the
 * merge fields are the ones the message was actually rendered with — not the contact's
 * current values, which may have been edited since.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationRunRecipientResponse(
        Long id,
        String email,
        Long contactId,
        Long personId,
        String personName,
        String greetingName,
        String title,
        Long companyId,
        String companyName,
        /** SENT | FAILED | PENDING | SKIPPED_DUPLICATE | SKIPPED_NOT_WORKING */
        String status,
        int attempts,
        String error,
        LocalDateTime sentAt) {
}
