package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * How far the retrieval index has got for this desk: which READY samples have a vector computed
 * from their current text, and whether an index run is going.
 *
 * <p>Progress is this desk's own. Another desk's run is shown as idle, never with its counts.
 * {@code lastError} and {@code lastFinishedAt} are null until a run has ended, and are dropped
 * from the JSON rather than sent as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisEmbeddingStatusResponse(
        /** EMBEDDING_URL is set. False means the index can be looked at but not built. */
        boolean enabled,
        /** The name the vectors are stored under. A change here makes every vector stale. */
        String model,
        /** READY samples on this desk: the ones that are ever embedded. */
        long ready,
        /** READY samples whose vector matches their current text. */
        long indexed,
        /** READY samples with no vector, or one computed from text that has since changed. */
        long stale,
        boolean running,
        /** Samples embedded so far in the current run, and how many the run set out to do. */
        int done,
        int total,
        /** Why the last run stopped early, if it did. */
        String lastError,
        LocalDateTime lastFinishedAt) {
}
