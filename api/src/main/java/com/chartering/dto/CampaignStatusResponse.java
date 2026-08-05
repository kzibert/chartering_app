package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * Live progress of the one campaign the API will run at a time. The UI polls this
 * while a send is in flight; {@code state} drives what it shows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignStatusResponse(
        /* IDLE, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, ABORTED */
        String state,
        boolean running,
        String subject,
        int total,
        int sent,
        int failed,
        int skipped,
        String currentEmail,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        /** Rough seconds remaining, from the configured pacing and what's left to send. */
        Long etaSeconds,
        String lastError,
        String message) {
}
