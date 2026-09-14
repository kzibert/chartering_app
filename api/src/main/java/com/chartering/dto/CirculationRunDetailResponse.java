package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A run opened from the History dropdown: the header line, the sending identity it went
 * out under, the composed circular before the merge, and every address it touched.
 *
 * <p>{@code composedHtml} still carries its {{placeholders}} — it is the template, not any
 * one recipient's copy. Ask for a specific recipient's message to see it merged.
 *
 * <p>{@code bodyHtml} and {@code footerId} are the same circular taken apart again, for
 * loading it back into the composer: the body alone, and the footer to pick beside it.
 * {@code footerId} is absent when the run had no footer, and also when its footer has been
 * edited or deleted since — then the old footer cannot be taken off the end, so it stays
 * inside {@code bodyHtml} and nothing is picked, rather than printing a footer twice.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationRunDetailResponse(
        CirculationRunResponse run,
        String composedHtml,
        String bodyHtml,
        Long footerId,
        String fromAddress,
        String fromName,
        String replyTo,
        String lastError,
        List<CirculationRunRecipientResponse> recipients) {
}
