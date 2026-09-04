package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for outgoing circulars. The SMTP transport itself is configured under the
 * standard {@code spring.mail.*} keys; everything here is about <em>how</em> we pace
 * and shape the send so the sending mailbox doesn't get rate-limited or blocklisted.
 *
 * <p>Defaults are deliberately conservative — Zoho's per-hour and per-day recipient
 * caps depend on the plan, and tripping them can suspend outgoing mail on the account.
 */
@Component
@ConfigurationProperties(prefix = "chartering.mail")
@Data
public class MailCampaignProperties {

    /** Master switch. When false the API refuses to start a campaign (useful for dev). */
    private boolean enabled = false;

    /**
     * Envelope/From address. Zoho rejects a From that isn't the authenticated account
     * or one of its verified aliases, so this normally equals spring.mail.username.
     */
    private String fromAddress;

    /** Display name shown to recipients, e.g. "Chartering Desk". */
    private String fromName = "Chartering";

    /** Reply-To, if replies should land somewhere other than the sending mailbox. */
    private String replyTo;

    /**
     * Address put in the List-Unsubscribe header. Bulk senders that omit this get
     * penalised by most providers; recipients also get a one-click opt-out instead
     * of a spam complaint, which is what actually damages sender reputation.
     */
    private String unsubscribeMailto;

    /**
     * The gap between two consecutive messages is drawn at random from
     * [minDelayMs, maxDelayMs] — never a fixed interval. A perfectly regular cadence is a
     * machine fingerprint; a spread of several seconds looks far more like human sending
     * and keeps the average rate low enough not to trip per-hour limits.
     */
    private long minDelayMs = 3000;

    /** Upper bound of the random gap. Values below {@code minDelayMs} are clamped to it. */
    private long maxDelayMs = 10_000;

    /**
     * How many recipients one run may cover. A campaign with more than this is not
     * refused — it is sent as a sequence of runs of at most this size, each separated by
     * {@link #batchPauseMs}. The ceiling is what keeps a single burst inside the
     * provider's per-hour allowance.
     */
    private int maxRecipientsPerCampaign = 200;

    /**
     * Quiet gap between one run and the next of the same campaign. This is what makes
     * splitting worth doing: six runs back to back are one burst wearing six hats.
     *
     * <p>It spreads the load across the hours, not across days — a campaign larger than
     * the mailbox's <em>daily</em> allowance is still larger than it, however it is paced.
     */
    private long batchPauseMs = 900_000; // 15 minutes

    /** Retries for a recipient that fails with a transient (4xx) SMTP error. */
    private int maxRetries = 2;

    /** Pause before a retry; doubles with each attempt. */
    private long retryBackoffMs = 10_000;

    /**
     * Circuit breaker: this many failures in a row aborts the whole run. Consecutive
     * failures usually mean the account got throttled or the credentials were revoked —
     * continuing to hammer SMTP in that state is what turns a throttle into a block.
     */
    private int abortAfterConsecutiveFailures = 5;

    /** Where the campaign log lives. Overwritten on a new run only if the last one fully succeeded. */
    private String logFile = "logs/campaign-current.log";

    /**
     * Which transport a <em>reply</em> leaves by — {@code SMTP} (the default, and the only
     * one before this existed) or {@code BREVO}.
     *
     * <p><b>Why this is an environment variable and not a setting</b>, when the same choice
     * for circulars deliberately is one. The circulars provider is a preference: a person
     * decides on Tuesday that this list should go through Brevo, and unticks it on Thursday.
     * This is not that. It answers "can this machine open an SMTP connection at all", which
     * is a fact about where the app is running and nothing the user gets an opinion about.
     *
     * <p>It also could not be a setting even if it wanted to be. Settings live in
     * {@code app_settings}, and the hosted deployment and the office one point at the
     * <em>same database</em> — so a stored value is necessarily the same value in both, and
     * the whole point here is that it must differ. A cloud instance whose host blocks
     * outbound SMTP needs Brevo; the desk running under compose, on a network that does not,
     * should carry on sending replies out of the mailbox itself.
     *
     * <p>Left alone, replies go over SMTP, which is what {@code MailReplyService} explains at
     * length and still prefers: the answer then comes from the address the correspondent
     * wrote to, threads on real headers, and is filed in the mailbox's own Sent folder.
     * Brevo gives up the last of those outright and part of the second — read that class
     * before switching a deployment over.
     */
    private String replyProvider;
}
