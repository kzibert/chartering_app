package com.chartering.service.mail;

import java.util.Locale;

/**
 * How circulars leave the building.
 *
 * <p>Two genuinely different postures, not two configurations of one thing:
 *
 * <ul>
 *   <li>{@link #SMTP} hands each message to the user's own mailbox over SMTP. The circular
 *       arrives from a real, human mailbox — best for deliverability into brokers' inboxes,
 *       and replies land where the sender expects. The price is the mailbox's own quotas:
 *       exceed them and outgoing mail on the whole account is suspended, which is why the
 *       pacing rails around it are deliberately slow.</li>
 *   <li>{@link #BREVO} posts each message to Brevo's transactional API. The provider owns
 *       the reputation and the throughput, so the same list goes out in minutes rather than
 *       an hour, and a bad address costs a bounce record rather than the mailbox.</li>
 * </ul>
 *
 * <p>The choice is per-installation and lives in Settings, not in the environment: it is a
 * decision the user makes and changes, not a deployment fact.
 */
public enum CircularProvider {

    SMTP("Mailbox (SMTP)"),
    BREVO("Brevo API");

    private final String label;

    CircularProvider(String label) {
        this.label = label;
    }

    /** Wording for the UI — what the Circulars tab shows as the provider in force. */
    public String label() {
        return label;
    }

    /**
     * Read a stored setting value. Anything unrecognised — including a row written by an
     * older version, or by hand — falls back to the mailbox flow, because that is the one
     * that was already working before this setting existed.
     */
    public static CircularProvider parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SMTP;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SMTP;
        }
    }
}
