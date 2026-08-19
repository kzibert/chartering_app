package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One line of the History dropdown: enough to recognise a run without loading it, and
 * enough to know whether it can be picked up again.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationRunResponse(
        Long id,
        String subject,
        String listName,
        String footerName,
        String state,
        int total,
        int sent,
        int failed,
        int skipped,
        /** Addresses queued and never reached — what a resume would send to. */
        int pending,
        /** True when the run stopped with somebody still to reach, so it can be continued. */
        boolean resumable,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String message) {
}
