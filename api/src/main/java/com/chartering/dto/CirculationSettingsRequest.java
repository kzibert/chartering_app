package com.chartering.dto;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The circulation knobs, as set from the Settings tab. Credentials are deliberately absent:
 * MAIL_USERNAME / MAIL_PASSWORD stay in the environment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CirculationSettingsRequest {

    /**
     * Envelope From. Providers reject a From that is not the authenticated mailbox or one
     * of its verified aliases, so this is editable but not free.
     *
     * <p>This and the SMTP fields are the server mailbox's, and only its owner sets them; for
     * anybody else they are ignored. So they are checked by {@code SettingsService} for that
     * one caller rather than by bean validation for everyone, which would refuse a colleague's
     * pacing change for lacking a From that is not theirs to send.
     */
    private String fromAddress;

    /** Display name recipients see. Optional — blank sends the bare address. */
    private String fromName;

    private String smtpHost;

    private int smtpPort;

    /** Shortest gap between two messages; the actual gap is random in [min, max]. */
    @Min(value = 0, message = "the shortest gap cannot be negative")
    private long minDelayMs;

    @Min(value = 0, message = "the longest gap cannot be negative")
    private long maxDelayMs;

    /** Recipients one run may cover; a bigger campaign is split into runs of this size. */
    @Min(value = 1, message = "the per-run cap must be at least 1")
    private int maxRecipientsPerCampaign;

    /** Quiet gap between one run of a split campaign and the next. 0 sends them back to back. */
    @Min(value = 0, message = "the pause between runs cannot be negative")
    private long batchPauseMs;
}
