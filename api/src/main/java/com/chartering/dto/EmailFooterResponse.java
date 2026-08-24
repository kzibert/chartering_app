package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmailFooterResponse(
        Long id,
        String name,
        String html,
        boolean defaultFooter,
        boolean replyDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
