package com.chartering.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A new message, written from a company's record rather than in answer to one.
 *
 * <p>The reply's shape less the quote, plus copies: the first address picked is who it is to,
 * the rest are on copy, the way somebody writing to a firm puts the person first and the desk
 * address beside them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailComposeRequest {

    @NotBlank(message = "a recipient address is required")
    @Email(message = "not a valid email address")
    private String to;

    @Size(max = 20, message = "at most 20 addresses on copy")
    private List<@Email(message = "not a valid email address") String> cc;

    @NotBlank(message = "a subject is required")
    @Size(max = 300, message = "subject must be at most 300 characters")
    private String subject;

    @NotBlank(message = "the message body is empty")
    private String bodyHtml;

    /** Footer to append, or null for none — never a fallback to a default. See MailReplyRequest. */
    private Long footerId;

    /**
     * The contact the {@code to} address is, when it was picked from the record. It is what
     * {{greeting}} and {{name}} are merged against; an address typed by hand merges against
     * nobody and the placeholders fall back the way they always do.
     */
    private Long contactId;
}
