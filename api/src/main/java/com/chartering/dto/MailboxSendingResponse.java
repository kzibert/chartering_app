package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * What the mailbox itself has sent today — the half of the day's outgoing volume that this
 * app does not produce and cannot count on its own.
 *
 * <p><b>Why this exists.</b> The circular counters answer "what did this app send", which
 * stopped being the same question as "what has this mailbox sent" the moment a reply could
 * be written in Outlook, on a phone, or in the webmail. The mail server knows: every one of
 * them leaves a copy in the Sent folder, which this app already syncs like any other folder.
 * So the figure is read from the synced mail rather than counted, and it therefore includes
 * mail nothing here ever saw being written.
 *
 * <p><b>Never add it to the circular counts.</b> The provider files this app's own SMTP
 * circulars into that same Sent folder, so the two overlap by an amount that depends on the
 * provider — Zoho copies them, some others do not. Read side by side they are two honest
 * answers to two questions; added together they are one wrong answer to neither.
 *
 * @param sentFolder     the Sent folder as the server names it — "Отправленные" on the
 *                       mailbox this was built against, which is why it is found by IMAP
 *                       SPECIAL-USE and not by the word "Sent"
 * @param sent           messages in it today, as of the last sync of that folder. Null when
 *                       the server reports no Sent folder, or none has been synced yet
 * @param folderSyncedAt when that folder was last read, so the UI can say how stale the
 *                       figure is: a reply written in Outlook is invisible here until the
 *                       next poll
 * @param replies        replies sent from this app today, counted from its own record and
 *                       so exact and immediate. These are inside {@code sent} as well, once
 *                       the folder syncs — which is why they are reported separately rather
 *                       than added to it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailboxSendingResponse(
        String sentFolder,
        Integer sent,
        LocalDateTime folderSyncedAt,
        int replies) {
}
