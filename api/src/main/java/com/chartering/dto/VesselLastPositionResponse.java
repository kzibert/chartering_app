package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The most recent position reported about a vessel, as her own record shows it.
 *
 * <p><b>Slim on purpose — no nested vessel.</b> {@link VesselPositionResponse} carries the
 * whole ship, because Open Fleet is a list of positions and every row there has to answer
 * "how big, how deep, geared?" on its own. Here the vessel is the parent of this object, and
 * embedding her inside her own latest position would double the payload and leave two copies
 * of one record on the page, inviting the question of which is authoritative.
 *
 * <p>{@code status} is sent and matters: this is the latest reading of <em>any</em> status,
 * not the latest live one. If she has since fixed, "last open" is still where she was last
 * reported free, and saying so beside the word FIXED is more use than an empty field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselLastPositionResponse(
        Long id,
        String status,

        Long openPortId,
        String openPortName,
        String openPortText,
        Long openAreaId,
        String openAreaCode,
        String openAreaName,

        LocalDate openFrom,
        LocalDate openTo,
        String openText,

        String lastCargo,
        String cargoPreferences,

        Long reportedByCompanyId,
        String reportedByCompanyName,
        OffsetDateTime reportedAt,
        /** Whole days since the reading — computed server-side so every view agrees on it. */
        long ageDays,

        String notes) {
}
