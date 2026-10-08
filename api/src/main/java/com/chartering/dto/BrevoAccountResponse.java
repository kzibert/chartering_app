package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The caller's own Brevo account, as the Settings card shows it. Never carries the key - only
 * its last four characters.
 *
 * @param source   {@code PERSONAL} - one they saved; {@code SERVER} - the environment's
 *                 {@code BREVO_API_KEY}, which is theirs because they own the server's mailbox;
 *                 {@code NONE}
 * @param canStore whether this server can store a key at all (CREDENTIALS_KEY)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BrevoAccountResponse(
        String source,
        boolean canStore,
        String keyHint,
        String senderAddress,
        String senderName) {
}
