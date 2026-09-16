package com.chartering.model;

/**
 * How a topic's items were made to fit the model's context window.
 *
 * <p>Chosen by what fits, never by preference: one pass is better whenever it is possible,
 * because every intermediate step is a place for a figure to be dropped or rounded.
 */
public enum FeedSummaryStrategy {
    /** Every selected item fitted one request beside the prompt and the answer. */
    SINGLE_PASS,
    /**
     * They did not: batches were read into notes, and the notes — condensed again as many
     * levels as it took — were summarised.
     */
    MAP_REDUCE
}
