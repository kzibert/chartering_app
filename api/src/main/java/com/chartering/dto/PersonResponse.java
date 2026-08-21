package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonResponse(
        Long id,
        String fullName,
        String title,
        String greetingName,
        Long companyId,
        String companyName,
        String notes,
        boolean legacy,
        /**
         * No longer works at this company. Every address of theirs is then off circulations
         * — left out of collection and dropped again at send time — while the record itself
         * stays exactly where it is.
         */
        boolean hasLeft) {
}
