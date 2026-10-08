package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The caller's own mailbox, as the Settings card shows it. Never carries the password - only
 * whether one is stored.
 *
 * @param source     {@code SERVER} - the mailbox configured in the environment, which is
 *                   theirs; {@code PERSONAL} - one they saved; {@code NONE}
 * @param canStore   whether this server can store a password at all (CREDENTIALS_KEY)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailAccountResponse(
        String source,
        boolean canStore,
        String emailAddress,
        String displayName,
        String imapHost,
        Integer imapPort,
        Boolean imapSsl,
        String smtpHost,
        Integer smtpPort,
        Boolean enabled,
        Boolean passwordSet) {
}
