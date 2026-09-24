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
        /**
         * Whether the reporting firm is already on her record in any capacity - owner or a
         * linked broker. Open Fleet offers to relate the two where it is false. Null where
         * nobody is named, and where the caller did not work it out (Match does not need it).
         */
        Boolean reporterLinked,

        boolean fromMail,
        /**
         * The board post she was read off, and the board's name.
         *
         * <p>No source kind beside it, unlike a cargo: what a broker reads on Open Fleet is who
         * reported her, and that is a column of its own — filled from the signature block off a
         * board exactly as it is filled from the sender out of the mail.
         */
        Long sourceFeedItemId,
        String sourceFeedName,
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
