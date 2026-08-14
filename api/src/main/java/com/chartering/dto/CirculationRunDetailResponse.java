package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A run opened from the History dropdown: the header line, the sending identity it went
 * out under, the composed circular before the merge, and every address it touched.
 *
 * <p>{@code composedHtml} still carries its {{placeholders}} — it is the template, not any
 * one recipient's copy. Ask for a specific recipient's message to see it merged.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationRunDetailResponse(
        CirculationRunResponse run,
        String composedHtml,
        String fromAddress,
        String fromName,
        String replyTo,
        String lastError,
        List<CirculationRunRecipientResponse> recipients) {
}
