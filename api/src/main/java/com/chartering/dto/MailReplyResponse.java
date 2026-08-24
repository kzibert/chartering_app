package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A reply that has gone out. Returned rather than a bare 201 so the tab can say what was
 * sent, to whom, and with which footer, without another round trip.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailReplyResponse(
        Long id,
        Long mailMessageId,
        String toAddress,
        String subject,
        String footerName,
        LocalDateTime sentAt) {
}
