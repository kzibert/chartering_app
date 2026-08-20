package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The read side of the mailbox: where incoming mail is fetched from, and how much of it.
 *
 * <p>Separate from {@link MailCampaignProperties} because reading and sending are separate
 * concerns with separate failure modes — a broken IMAP host must not stop a circular going
 * out, and a suspended sending quota must not stop the inbox refreshing. They do share
 * credentials by default: {@code IMAP_USERNAME}/{@code IMAP_PASSWORD} fall back to the SMTP
 * ones in application.yml, because the usual case is one mailbox read and written by the
 * same account, and making that the default means one pair of credentials to configure.
 *
 * <p><b>The mailbox is opened read-only.</b> Nothing in the app writes to it: no flags, no
 * moves, no deletes. Folders and rules are the app's own (see {@code MailFolder}), so the
 * worst a mistake here can do is show the wrong mail on a screen.
 */
@Component
@ConfigurationProperties(prefix = "chartering.mailbox")
@Data
public class MailboxProperties {

    /**
     * Master switch for the whole Mailbox tab. Off by default, like {@code MAIL_ENABLED}:
     * an app that has not been given a mailbox to read should say so plainly rather than
     * retry a login it was never configured for every minute.
     */
    private boolean enabled = false;

    /** IMAP host. Defaults to Zoho's EU data centre, matching the SMTP default. */
    private String host = "imap.zoho.eu";

    /** 993 is implicit SSL, which is what {@link #ssl} being on by default assumes. */
    private int port = 993;

    private String username;

    private String password;

    /** Implicit SSL (993). Turn off only for a plain or STARTTLS port. */
    private boolean ssl = true;

    /** The server-side folder to read. Only this one is synced. */
    private String folder = "INBOX";

    /**
     * How often the poller wakes. Five minutes by default: an inbox is not a chat window,
     * and every poll is a login against a mailbox that may well rate-limit them.
     */
    private long pollIntervalMs = 300_000;

    /**
     * On the very first sync — when there is no stored UID to resume from — how far back to
     * reach. A bounded window rather than "everything": a mailbox with fifteen years of mail
     * in it would otherwise spend its first sync downloading all of it, and the messages
     * worth linking to a company are the recent ones.
     */
    private int initialSyncDays = 30;

    /**
     * Ceiling on one poll. A backlog is not fetched in a single pass; it is fetched over
     * several, oldest first, so a first run against a busy mailbox cannot hold the
     * connection open for an hour or exhaust the heap.
     */
    private int maxMessagesPerPoll = 200;

    /**
     * Longest body kept, per part. Bodies are stored to make them searchable and readable;
     * a message carrying a megabyte of quoted history is neither more searchable nor more
     * readable for having all of it, and the table is the thing that pays.
     */
    private int maxBodyChars = 200_000;

    /** Connect/read timeouts. A hung mailbox must not hold the poller thread forever. */
    private int connectTimeoutMs = 15_000;

    private int readTimeoutMs = 30_000;
}
