package com.chartering.dto;

/**
 * A folder with its counts, for the folder rail.
 *
 * <p>The Inbox comes back as one of these too, with a null id — it is not a row in the
 * table, but it is a folder as far as the rail is concerned, and giving it the same shape
 * keeps "unfiled" from needing its own special case in the UI.
 */
public record MailFolderResponse(
        /** null = the Inbox (unfiled mail). */
        Long id,
        String name,
        String notes,
        int sortOrder,
        long total,
        long unread) {
}
