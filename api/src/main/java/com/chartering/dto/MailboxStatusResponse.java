package com.chartering.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Whether the mailbox is actually being read, and how the last attempt went.
 *
 * <p>The tab leads with this rather than hiding it in a log. An inbox that quietly stopped
 * syncing three days ago looks exactly like an inbox where nothing has arrived, and only one
 * of those is worth acting on.
 */
public record MailboxStatusResponse(
        /** The IMAP_ENABLED master switch. */
        boolean enabled,
        /** enabled, and every credential present — i.e. a sync would actually be attempted. */
        boolean configured,
        /** What is missing when it is not, named as the environment variable to set. */
        List<String> missingSettings,
        String host,
        /** The folder read first on every poll, and the one the tab opens on. */
        String folder,
        /** How many folders the mailbox has, all of which are mirrored. */
        int folderCount,
        /** The account the app logs in as. Never the password. */
        String username,
        /** A sync is running right now — the poller's own, or one the user asked for. */
        boolean syncing,
        /** The most recent folder read, of them all. */
        LocalDateTime lastSyncAt,
        /** OK | FAILED across every folder: one folder failing makes the whole sync FAILED. */
        String lastStatus,
        /** The first folder's error, named, since a failure is now always a folder's failure. */
        String lastError,
        /** Summed over the last pass of every folder. */
        int lastFetched,
        int lastStored,
        long pollIntervalMs,
        long totalMessages,
        long unread) {
}
