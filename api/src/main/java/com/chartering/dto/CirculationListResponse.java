package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A circulation list. {@code entries} is populated only by the single-list endpoints —
 * the picker lists dozens of lists and needs {@code entryCount}, not thousands of rows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationListResponse(
        Long id,
        String name,
        /** true for the unnamed "current list" the tabs collect into. */
        boolean draft,
        String notes,
        int entryCount,
        List<CirculationListEntryResponse> entries,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
