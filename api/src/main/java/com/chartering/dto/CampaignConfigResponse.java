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
        /**
         * Which flow sends circulars right now — {@code SMTP} or {@code BREVO}. Reported
         * because it changes what the recipient sees and whose quota is being spent, and
         * neither is visible from the compose screen otherwise.
         */
        String provider,
        /** The same choice, worded for display: "Mailbox (SMTP)" or "Brevo API". */
        String providerLabel,
        /**
         * Which transport a <em>reply</em> leaves by, which is a different question and
         * usually a different answer — a reply ignores the circulars provider entirely.
         * Reported because the Reply box has to say where the message will come from, and
         * because the two routes differ in ways the person answering a broker cares about:
         * under Brevo the message never reaches the mailbox's Sent folder.
         */
        String replyProvider,
        String replyProviderLabel,
        /**
         * What is missing for a reply specifically. Not the same list as
         * {@link #missingSettings} once the two routes differ: a deployment sending
         * circulars through Brevo may still have every SMTP setting a reply needs, and one
         * replying through Brevo needs an API key rather than a mailbox password.
         */
        List<String> replyMissingSettings,
        /** Only meaningful under SMTP; kept in the payload so switching back shows the endpoint. */
        String smtpHost,
        int smtpPort,
        String username,
        String fromAddress,
        String fromName,
        String replyTo,
        /** Gap between messages is random in [minDelayMs, maxDelayMs] — never fixed. */
        long minDelayMs,
        long maxDelayMs,
        /** Recipients per run; a longer list is sent as several runs of this size. */
        int maxRecipientsPerCampaign,
        /** Quiet gap between those runs, so the UI can show the plan before sending. */
        long batchPauseMs,
        boolean unsubscribeConfigured) {
}
