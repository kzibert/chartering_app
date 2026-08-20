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
        /** The mailbox being read, e.g. INBOX. */
        String folder,
        /** The account the app logs in as. Never the password. */
        String username,
        /** A sync is running right now — the poller's own, or one the user asked for. */
        boolean syncing,
        LocalDateTime lastSyncAt,
        /** OK | FAILED, or null if it has never run. */
        String lastStatus,
        String lastError,
        int lastFetched,
        int lastStored,
        long pollIntervalMs,
        long totalMessages,
        long unread) {
}
