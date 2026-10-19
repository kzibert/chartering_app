package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * What the last sweep did — the answer to "Parse now", and what the tab polls afterwards.
 *
 * <p>A sweep is minutes of somebody else's GPU, so the request that starts one returns
 * immediately and this is fetched until {@code running} goes false. Same shape either way,
 * so the screen renders one thing whether it is watching a run or reporting a finished one.
 *
 * @param unreachable the difference between "nothing to read" and "could not read", which
 *                    are the two outcomes most worth telling apart and look identical in a
 *                    count of zero
 * @param mode        BATCH (one batch, the button and the timer), ALL ("Parse everything") or
 *                    SELECTED ("Parse selected") — what the run was asked to do
 * @param progress    messages handled so far in a run in progress; present only while this
 *                    desk's run is going
 * @param total       how many were asked for, known only for SELECTED
 * @param stopRequested whether a stop has been asked for and not yet taken effect
 * @param stopped     whether the finished run ended early on a stop request
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SweepResponse(
        boolean running,
        Integer read,
        Integer failed,
        Integer skipped,
        Integer positions,
        Integer cargoes,
        Integer items,
        Boolean unreachable,
        String message,
        OffsetDateTime finishedAt,
        String mode,
        Integer progress,
        Integer total,
        Boolean stopRequested,
        Boolean stopped) {
}
