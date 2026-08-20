package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Circulation settings in force, alongside the configured defaults they can be reset to,
 * so the screen can show what a value would revert to without a second request.
 *
 * <p>The defaults shown depend on the provider: the pacing that suits a personal mailbox is
 * not the pacing that suits Brevo, so switching provider changes both the values in force
 * and what "reset" means.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationSettingsResponse(
        /** Which flow sends circulars: {@code SMTP} or {@code BREVO}. */
        String provider,
        /** The same choice, worded for display. */
        String providerLabel,
        /** True when the Brevo API key is present in the environment, so the UI can warn before the switch is used. */
        boolean brevoConfigured,
        String fromAddress,
        String fromName,
        String smtpHost,
        int smtpPort,
        long minDelayMs,
        long maxDelayMs,
        int maxRecipientsPerCampaign,
        long batchPauseMs,
        /** true when any of these differ from the configured defaults for this provider */
        boolean customised,
        CirculationSettingsResponse defaults) {

    /** The defaults block itself has no nested defaults — that would recurse forever. */
    public static CirculationSettingsResponse defaultsOnly(String provider, String providerLabel,
                                                           String fromAddress, String fromName,
                                                           String host, int port, long minDelay,
                                                           long maxDelay, int maxRecipients,
                                                           long batchPause) {
        return new CirculationSettingsResponse(provider, providerLabel, false, fromAddress, fromName,
                host, port, minDelay, maxDelay, maxRecipients, batchPause, false, null);
    }
}
