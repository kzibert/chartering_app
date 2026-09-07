package com.chartering.dto;

import com.chartering.model.ParseStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * One email the parser has read, and what came of it.
 *
 * <p>The log behind the Intake tab's second view. It answers the question a review queue
 * cannot: not "what needs me" but "did it read this morning's mail at all, and what did it
 * make of it" — which is how a broker notices that a broker's list is being classified as
 * {@code other} and quietly producing nothing.
 *
 * @param rawJson only on the detail endpoint, never in the list. It is the model's whole
 *                answer and can be tens of kilobytes; a page of twenty would be a megabyte
 *                of JSON to render a table of counts
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsedEmailResponse(
        Long id,
        Long mailMessageId,
        ParseStatus status,
        String emailType,
        String fromAddress,
        String fromName,
        String subject,
        LocalDateTime receivedAt,
        int positionsApplied,
        int cargoesApplied,
        int itemsRaised,
        String modelName,
        Integer durationMs,
        Integer promptChars,
        int attempts,
        String error,
        OffsetDateTime parsedAt,
        String rawJson) {
}
