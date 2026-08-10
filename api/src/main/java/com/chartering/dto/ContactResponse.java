package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContactResponse(
        Long id,
        Long personId,
        String personName,
        String title,
        String greetingName,
        Long companyId,
        String companyName,
        String contactKind,
        String contactValue,
        String notes,
        boolean confirmed,
        OffsetDateTime confirmedAt,
        String confirmedBy,
        String confirmNotes,
        boolean banned,
        boolean legacy,
        boolean main,
        boolean working) {
}
