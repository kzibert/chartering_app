package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A one-to-one message as it would go out, without sending it: the same footer, quote, merge
 * and sanitising the send applies, so what the preview shows is the string the mail server
 * would be handed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailPreviewResponse(
        String fromName,
        String fromAddress,
        String to,
        List<String> cc,
        String subject,
        String html,
        String footerName) {
}
