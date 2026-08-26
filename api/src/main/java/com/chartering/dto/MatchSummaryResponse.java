package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One live cargo and how much tonnage suits it — the Match tab's landing view.
 *
 * <p>The counts are the whole point: a desk with nine cargoes on it wants to know which one
 * has nothing against it before it wants to know which ship. {@code untouched} is the number
 * of suitable ships nothing has been decided about yet, which is the figure that says
 * whether there is work here or whether it has already been done.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchSummaryResponse(
        CargoResponse cargo,
        int suitable,
        int untouched,
        int ruledOut,
        /** The best score among the suitable ships, or 0 when none suit. */
        int bestScore) {
}
