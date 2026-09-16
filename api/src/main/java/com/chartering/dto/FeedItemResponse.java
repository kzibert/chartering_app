package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedItemResponse(
        Long id,
        Long sourceId,
        String sourceName,
        LocalDateTime publishedAt,
        String title,
        String text,
        String url,
        String author,
        LocalDateTime fetchedAt) {
}
