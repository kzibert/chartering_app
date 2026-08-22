package com.chartering.dto;

import java.time.LocalDateTime;

/**
 * One row of the message list. Carries the snippet rather than the body: the list shows
 * hundreds of rows and the bodies are the largest thing in the table, so the full message
 * is fetched only when one is opened.
 */
public record MailMessageResponse(
        Long id,
        String fromAddress,
        String fromName,
        String subject,
        String snippet,
        LocalDateTime sentAt,
        LocalDateTime receivedAt,
        boolean read,
        boolean hasAttachments,
        /** The folder the mail server has it in — where the mailbox's own filters put it. */
        String imapFolder,
        Long folderId,
        String folderName,
        /** Which rule filed it, when one did — the answer to "why is this in here?". */
        Long filedByRuleId,
        Long companyId,
        String companyName,
        Long personId,
        String personName,
        /** true = the company link was set by hand and no re-link pass will overwrite it. */
        boolean linkManual) {
}
