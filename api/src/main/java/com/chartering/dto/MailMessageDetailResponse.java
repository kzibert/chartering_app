package com.chartering.dto;

/**
 * A message opened for reading: the list row, plus everything left out of it.
 *
 * <p>{@code bodyHtml} has been through {@code HtmlSanitizer} on the way out — the same
 * treatment circular templates get, for the same reason: the browser is about to render
 * markup that arrived from outside, and scripts, frames and inline handlers have no business
 * in a message body.
 */
public record MailMessageDetailResponse(
        MailMessageResponse message,
        String toAddresses,
        String ccAddresses,
        /** Sanitized. Null when the message carried no HTML part, in which case use the text. */
        String bodyHtml,
        String bodyText,
        String attachmentNames,
        Integer sizeBytes,
        String messageId) {
}
