package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One reported opening position, with the vessel it is about.
 *
 * <p>The whole vessel rides along rather than just her name. Open Fleet is a fleet list —
 * the questions asked of a row are "how big, how deep, geared?" and every one of them is
 * answered from the vessel record. Sending the id and making the screen fetch each would be
 * one request per row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselPositionResponse(
        Long id,
        VesselResponse vessel,
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
        Long reportedByPersonId,
        String reportedByPersonName,

        boolean fromMail,
        Long sourceMailMessageId,
        OffsetDateTime reportedAt,

        /**
         * How old this reading is, in whole days. Computed rather than stored, and sent
         * rather than left to the browser: staleness is the first thing this screen has to
         * show — "SPOT AT MARMARA" was true on Monday and is a lie by Friday — and a figure
         * derived in one place cannot disagree with itself between the table and the card.
         */
        long ageDays,

        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
