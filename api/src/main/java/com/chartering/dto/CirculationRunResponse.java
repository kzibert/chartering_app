package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** One line of the History dropdown: enough to recognise a run without loading it. */
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
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String message) {
}
