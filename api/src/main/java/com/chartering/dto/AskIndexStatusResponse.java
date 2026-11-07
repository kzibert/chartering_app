package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * How far this desk's index of its own text has got. Counts are of sources (a message, a post, a
 * cargo, a position), not of chunks, because that is what the person indexing is waiting for.
 *
 * <p>Progress is this desk's own; another desk's run is shown as idle. {@code lastError} and
 * {@code lastFinishedAt} are dropped from the JSON until a run has ended.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskIndexStatusResponse(
        /** The embedding model the index is built with. A change here makes everything pending again. */
        String model,
        /** Sources with a current index entry for this model. */
        long indexedSources,
        /** Sources that need indexing: new, or changed since they were indexed. */
        long pendingSources,
        boolean running,
        /** Sources indexed so far in the current run, and how many the run set out to do. */
        int done,
        int total,
        /** Why the last run stopped early, if it did. */
        String lastError,
        OffsetDateTime lastFinishedAt) {
}
