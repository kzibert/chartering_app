package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What the server is configured to do, so the Circulars tab can show the real pacing
 * and refuse to offer a Send button that would only fail. Never includes the password.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignConfigResponse(
        boolean enabled,
        /** True when every required setting is present — i.e. a send would at least be attempted. */
        boolean ready,
        /** Human-readable names of the settings still missing, for the UI to display. */
        List<String> missingSettings,
        String smtpHost,
        int smtpPort,
        String username,
        String fromAddress,
        String fromName,
        String replyTo,
        /** Gap between messages is random in [minDelayMs, maxDelayMs] — never fixed. */
        long minDelayMs,
        long maxDelayMs,
        int maxRecipientsPerCampaign,
        boolean unsubscribeConfigured) {
}
