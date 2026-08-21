package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Everything the Brevo transactional API needs, plus the pacing baseline that applies while
 * Brevo is the provider in force.
 *
 * <p><b>Why the pacing defaults live here rather than beside the SMTP ones.</b> The two
 * providers are protecting different things. Sending through a personal mailbox, the thing
 * at risk is that mailbox — trip its hourly cap and outgoing mail is suspended for
 * everything, not just circulars, so the defaults there are measured in seconds per message.
 * Brevo exists to be sent through in bulk: its own infrastructure absorbs the rate, and the
 * only ceiling that matters is the plan's daily allowance, which no amount of spacing
 * changes. Carrying the mailbox's three-to-ten-second gap over to Brevo would turn a
 * four-minute circulation into an hour for no benefit at all.
 *
 * <p>The user still sees one set of knobs and can still tune them; these are only what those
 * knobs read when nothing has been changed, and what "Reset to defaults" restores.
 */
@Component
@ConfigurationProperties(prefix = "chartering.brevo")
@Data
public class BrevoProperties {

    /**
     * The v3 API key, from Brevo's SMTP &amp; API screen. Stays in the environment and is
     * never stored in the settings table or served to the browser — it is a credential with
     * full send rights on the account.
     */
    private String apiKey;

    /** API root. Configurable only so a test can point it at a stub. */
    private String baseUrl = "https://api.brevo.com/v3";

    /** Giving up on the TCP connect. Short: an unreachable API should fail preflight, not hang. */
    private int connectTimeoutMs = 10_000;

    /**
     * Giving up on the response. Generous, because a timeout here is genuinely ambiguous —
     * Brevo may well have accepted and queued the message we are about to record as failed.
     */
    private int readTimeoutMs = 30_000;

    /**
     * The plan's daily send ceiling, used as the denominator of the "left today" figure.
     *
     * <p>Configured rather than derived. Brevo publishes only the remainder of the allowance,
     * never the allowance itself, and the obvious reconstruction — today's statistics plus
     * what is left — does not hold: the statistics report counts every message Brevo
     * <em>accepted</em>, while the allowance is spent only by the ones it actually
     * <em>sends</em>, so a blocked or invalid address inflates the total by one each. The
     * report is also eventually consistent, and lags by minutes mid-campaign, which made the
     * derived ceiling drift by tens between one refresh and the next.
     *
     * <p>300 is the free tier. Change it here when the plan changes; a value of zero or less
     * means "this plan has no daily ceiling", and the quota figure is then hidden rather than
     * shown against a number that does not apply.
     */
    private int dailyLimit = 300;

    // ---- pacing baseline while Brevo is the provider (see the class note above) ----

    /** Shortest gap between two messages. Still non-zero: bursts are what trip rate limits. */
    private long minDelayMs = 200;

    private long maxDelayMs = 800;

    /**
     * Recipients one run may cover. Higher than the mailbox default because the per-run
     * ceiling exists to keep a burst inside a provider's hourly allowance, and Brevo's is
     * measured in thousands.
     */
    private int maxRecipientsPerCampaign = 500;

    /** Quiet gap between runs of a split campaign. */
    private long batchPauseMs = 60_000;
}
