package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * An email added to the corpus by hand.
 *
 * <p>Worth having even though capture from the mailbox is the normal route: a machine
 * working offline has no IMAP configured and would otherwise have no way to start a corpus
 * at all, and an example somebody forwarded as a screenshot or pasted into a chat has no
 * message in the mailbox to capture from.
 */
public record AnalysisPasteRequest(
        @Size(max = 320) String fromAddress,
        @Size(max = 255) String fromName,
        String subject,
        /** When it arrived, if known. Defaults to now — this is provenance, not a deadline. */
        LocalDateTime receivedAt,
        @NotBlank(message = "the email text is the sample; there is nothing to keep without it")
        String bodyText,
        String notes) {
}
