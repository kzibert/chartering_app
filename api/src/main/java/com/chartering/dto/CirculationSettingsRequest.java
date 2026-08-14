package com.chartering.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
     */
    @NotBlank(message = "a From address is required")
    @Email(message = "not a valid email address")
    private String fromAddress;

    /** Display name recipients see. Optional — blank sends the bare address. */
    private String fromName;

    @NotBlank(message = "SMTP host is required")
    private String smtpHost;

    @Min(value = 1, message = "SMTP port must be between 1 and 65535")
    @Max(value = 65535, message = "SMTP port must be between 1 and 65535")
    private int smtpPort;

    /** Shortest gap between two messages; the actual gap is random in [min, max]. */
    @Min(value = 0, message = "the shortest gap cannot be negative")
    private long minDelayMs;

    @Min(value = 0, message = "the longest gap cannot be negative")
    private long maxDelayMs;

    @Min(value = 1, message = "the per-run cap must be at least 1")
    private int maxRecipientsPerCampaign;
}
