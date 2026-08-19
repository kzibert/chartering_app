package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * Live progress of the one campaign the API will run at a time. The UI polls this
 * while a send is in flight; {@code state} drives what it shows.
 *
 * <p>In-memory only, so it reports IDLE after an API restart even when a run was cut off
 * mid-send. What survives a restart is the run itself — ask {@code /campaigns/resumable}
 * for the circulations that can still be carried on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignStatusResponse(
        /* IDLE, RUNNING, PAUSED, INTERRUPTED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, ABORTED */
        String state,
        boolean running,
        /** The circulation-history run this progress belongs to; what resume is called with. */
        Long runId,
        String subject,
        int total,
        int sent,
        int failed,
        int skipped,
        String currentEmail,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        /** Rough seconds remaining, from the configured pacing and what's left to send. */
        Long etaSeconds,
        String lastError,
        String message,
        /**
         * Which run of the campaign is in flight, 1-based, and how many there are. A list
         * that fits the per-run cap is one run of one — the batch fields are always
         * meaningful, so the UI never has to special-case the unsplit case.
         */
        int batch,
        int batchCount,
        /** True between runs: nothing is being sent, and the next run starts at nextBatchAt. */
        boolean paused,
        LocalDateTime nextBatchAt,
        /**
         * True when this run stopped with somebody still to reach, so it can be carried on.
         * Only ever true once the worker has stopped — mid-run the recipient rows are still
         * moving, and a resume then would race the send.
         */
        boolean resumable) {
}
