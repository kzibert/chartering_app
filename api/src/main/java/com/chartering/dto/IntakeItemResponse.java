package com.chartering.dto;

import com.chartering.model.IntakeItemKind;
import com.chartering.model.IntakeItemStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * One row of the review queue.
 *
 * <p>Carries the email it came out of — sender, subject, when it arrived — because that is
 * how a broker recognises what they are being asked about. "PACIFIC DAWN, DWT disagrees" is
 * a question about a spreadsheet; "PACIFIC DAWN, DWT disagrees, from Interscan's Tuesday
 * list" is a question about the market, and the second one can be answered from memory.
 *
 * @param payload the kind-specific detail — {@code IntakePayloads} — passed through as JSON
 *                rather than flattened into fields here. The three kinds share nothing but
 *                an id, and a response record with every field of all three would be mostly
 *                nulls on every row
 * @param counts  a short summary of the payload for the list ("3 fields differ"), so the
 *                table is readable without opening each drawer
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntakeItemResponse(
        Long id,
        IntakeItemKind kind,
        IntakeItemStatus status,
        String subjectLabel,
        String summary,
        Long vesselId,
        Long cargoId,
        Long mailMessageId,
        String fromAddress,
        String fromName,
        String mailSubject,
        LocalDateTime receivedAt,
        JsonNode payload,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        String resolvedBy,
        String resolutionNote) {
}
