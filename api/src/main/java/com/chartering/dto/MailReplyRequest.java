package com.chartering.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A reply as the Mailbox tab composed it, before the footer and the quote are added. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailReplyRequest {

    /**
     * Where it goes. Prefilled from the sender of the message being answered, and editable:
     * a broker who writes from a personal address often wants the answer at the desk one,
     * and only the person reading the thread knows which.
     */
    @NotBlank(message = "a recipient address is required")
    @Email(message = "not a valid email address")
    private String to;

    @NotBlank(message = "a subject is required")
    @Size(max = 300, message = "subject must be at most 300 characters")
    private String subject;

    @NotBlank(message = "the message body is empty")
    private String bodyHtml;

    /**
     * Footer to append, or null for none. Null means <em>no footer</em> and is not a
     * fallback to the reply default — the composer resolved that when it opened, and a send
     * that quietly re-adds a footer the user just removed would be wrong.
     */
    private Long footerId;

    /**
     * Quote the message being answered underneath. Absent counts as true: a reply with no
     * quote is a deliberate choice, and the shape a curl or a test would take by omission
     * should be the ordinary one.
     */
    private Boolean includeOriginal;
}
