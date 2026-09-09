package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Whether looking a hull up on the open web is part of this deployment.
 *
 * <p>It answers whether the switch is on and nothing else, and it answers whatever the switch
 * says — the same reason {@code GET /intake/status} and {@code GET /analysis/status} always
 * answer while every other endpoint of their feature 404s. A screen has to know whether a card
 * exists before it can decide not to draw it, and finding out by calling something else and
 * reading a 404 puts an error toast on every vessel opened.
 *
 * <p>{@code provider} is which source is configured, for saying so on the card. Free text
 * because the source is a deployment's choice: a paid API replacing a scraped page is the
 * expected direction of travel and should not need a new enum value.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselLookupStatusResponse(boolean enabled, String provider) {
}
