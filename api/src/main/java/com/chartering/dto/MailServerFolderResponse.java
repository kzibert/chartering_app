package com.chartering.dto;

import java.time.LocalDateTime;

/**
 * One folder of the mail server's own tree, with both counts and the state of its sync.
 *
 * <p>Flat, with {@code parentName} pointing at the row above: the rail builds the tree from
 * it, and a flat list is what keeps paging, ordering and the counts query simple on this
 * side. Zoho's tree is one level deep today; nothing here assumes that.
 *
 * <p>There are two pairs of counts on purpose. {@code total}/{@code unread} are the app's —
 * the mail actually synced out of this folder. {@code serverTotal}/{@code serverUnseen} are
 * what the server last reported it holds. A folder showing 26 on the server and nothing here
 * has not been reached yet, and without both numbers that is indistinguishable from an empty
 * folder.
 */
public record MailServerFolderResponse(
        /** The IMAP full name, decoded — 'DMARC Reports', 'Корзина', 'Brokers/Handy'. */
        String fullName,
        /** The last path segment, which is what the rail shows. */
        String displayName,
        String parentName,
        /** INBOX | SENT | DRAFTS | JUNK | TRASH | ARCHIVE, or null for the owner's own. */
        String specialUse,
        /** \Noselect: a branch of the tree that holds no mail of its own. */
        boolean selectable,
        /** Still listed by the server. False for a folder deleted in Zoho since. */
        boolean present,
        int sortOrder,
        /** Synced into the app. */
        long total,
        long unread,
        /** What the server said it holds. Null until it has been listed once. */
        Integer serverTotal,
        Integer serverUnseen,
        /** The sync cursor for this folder, from mail_sync_state. Null = never read. */
        LocalDateTime lastSyncAt,
        /** OK | FAILED for this folder alone: one bad folder does not stop the others. */
        String lastStatus,
        String lastError) {
}
