package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmailTemplateResponse(
        Long id,
        String name,
        String subject,
        String bodyHtml,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
