package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One test and its answer, as the screen shows it.
 *
 * <p>{@code detail} always names the actual figures — "Draws 7.9m, berth takes 7.0m" rather
 * than "failed draft check". The whole value of this screen is that a broker can disagree
 * with it, and they can only disagree with a reason they can read.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchCheckResponse(
        String code,
        String label,
        /** PASS, FAIL or UNKNOWN. UNKNOWN means we hold no data, not that she does not fit. */
        String verdict,
        int weight,
        String detail) {
}
