package com.chartering.service.mail;

import com.chartering.dto.CampaignRecipientRequest;
import com.chartering.service.SettingsService.CirculationSettings;

import java.util.List;

/**
 * A way of putting one circular in front of one recipient.
 *
 * <p>Implementations own everything provider-specific — building the message, the transport,
 * the credentials, and the translation of the provider's error vocabulary into
 * {@link CircularSendException}. Everything the campaign does <em>around</em> a send —
 * pacing, retries, the circuit breaker, history, pause and resume — is the same whichever
 * one is in force, and lives in {@code EmailCampaignService}.
 *
 * <h2>Why binding is a separate step</h2>
 * <p>A campaign resolves its settings once, when it starts, so that editing Settings
 * mid-send cannot change what half the recipients get. {@link #bind} makes that explicit:
 * the run holds a {@link Bound} handle built from the settings it began with, and every
 * message it sends afterwards goes through that handle rather than re-reading anything.
 */
public interface CircularSender {

    CircularProvider provider();

    /**
     * Human-readable names of the settings this provider still needs before it could send.
     * Empty means a send would at least be attempted — not that it will succeed, which only
     * {@link Bound#verify()} can tell you.
     */
    List<String> missingSettings(CirculationSettings cfg);

    /**
     * Freeze a transport for one run. Cheap enough to call per campaign; never called per
     * message, because a connection or a client rebuilt 200 times is 200 chances to pick up
     * a setting the run was supposed to be insulated from.
     */
    Bound bind(CirculationSettings cfg);

    /** The transport a single run sends through, together with the settings it froze. */
    interface Bound {

        CircularProvider provider();

        /**
         * Preflight the credentials and reachability before the first message.
         *
         * <p>Worth a round trip: a bad password caught here is an error message, whereas the
         * same password caught at message 1 of 200 is a half-sent campaign, a log full of
         * identical auth failures, and a provider that has now seen us fail to authenticate
         * repeatedly.
         *
         * @throws com.chartering.exception.MailNotConfiguredException if the provider cannot be reached or refuses us
         */
        void verify();

        /**
         * Send one personalised copy. The subject and body arrive as templates — the merge
         * happens here, so the same placeholders work whichever provider is in force.
         *
         * @throws CircularSendException classified so the caller knows whether to retry,
         *                               skip the address, or abort the run
         */
        void send(CampaignRecipientRequest recipient, String subjectTemplate, String htmlTemplate);
    }
}
