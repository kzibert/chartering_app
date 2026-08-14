package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One address on a list, with the mail-merge fields snapshotted when it was collected. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationListEntryResponse(
        Long id,
        Long contactId,
        String email,
        Long personId,
        String personName,
        String greetingName,
        String title,
        Long companyId,
        String companyName) {
}
