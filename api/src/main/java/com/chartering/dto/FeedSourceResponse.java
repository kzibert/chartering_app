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
        /**
         * Whether the parser reads this source's posts as circulars. Separate from
         * {@code enabled}: that is whether the page is fetched, this is what is done with it.
         */
        boolean intoIntake,
        LocalDateTime lastFetchedAt,
        /** Why the last fetch failed; absent once one succeeds. */
        String lastError,
        Integer lastNewItems,
        long itemCount) {
}
