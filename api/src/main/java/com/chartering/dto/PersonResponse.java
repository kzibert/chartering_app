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
        String notes) {
}
