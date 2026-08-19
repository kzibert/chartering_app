package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Circulation settings in force, alongside the configured defaults they can be reset to,
 * so the screen can show what a value would revert to without a second request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationSettingsResponse(
        String fromAddress,
        String fromName,
        String smtpHost,
        int smtpPort,
        long minDelayMs,
        long maxDelayMs,
        int maxRecipientsPerCampaign,
        long batchPauseMs,
        /** true when any of these differ from the configured defaults */
        boolean customised,
        CirculationSettingsResponse defaults) {

    /** The defaults block itself has no nested defaults — that would recurse forever. */
    public static CirculationSettingsResponse defaultsOnly(String fromAddress, String fromName,
                                                           String host, int port, long minDelay,
                                                           long maxDelay, int maxRecipients,
                                                           long batchPause) {
        return new CirculationSettingsResponse(fromAddress, fromName, host, port, minDelay,
                maxDelay, maxRecipients, batchPause, false, null);
    }
}
