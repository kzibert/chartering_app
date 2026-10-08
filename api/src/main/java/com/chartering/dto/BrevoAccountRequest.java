package com.chartering.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for PUT /api/v1/me/brevo-account. A blank key on a later save keeps the stored one. */
@Data
public class BrevoAccountRequest {

    @Size(max = 500, message = "must be at most 500 characters")
    private String apiKey;

    /** A sender verified on that Brevo account. Blank sends as the mailbox address. */
    @Email(message = "must be an email address")
    @Size(max = 255, message = "must be at most 255 characters")
    private String senderAddress;

    @Size(max = 200, message = "must be at most 200 characters")
    private String senderName;
}
