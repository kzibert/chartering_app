package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Text pasted into Intake to be read: a cargo offer, a position, a vessel's description, a
 * company's full style — or all four in one forwarded chain.
 *
 * <p>Subject and date are optional and exist because the model was trained on emails and reads
 * a date to anchor "prompt" and "end month" against. Left out, the date is today.
 */
public record IntakePasteRequest(
        @NotBlank(message = "paste the text to read")
        @Size(max = 60_000, message = "that is more than one email's worth of text")
        String text,
        @Size(max = 300) String subject,
        LocalDateTime receivedAt) {
}
