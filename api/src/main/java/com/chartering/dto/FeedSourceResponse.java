package com.chartering.dto;

import com.chartering.model.FeedSourceKind;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedSourceResponse(
        Long id,
        String name,
        FeedSourceKind kind,
        String url,
        String parserKey,
        boolean enabled,
        LocalDateTime lastFetchedAt,
        /** Why the last fetch failed; absent once one succeeds. */
        String lastError,
        Integer lastNewItems,
        long itemCount) {
}
