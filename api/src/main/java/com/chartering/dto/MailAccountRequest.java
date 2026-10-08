package com.chartering.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for PUT /api/v1/me/mail-account. */
@Data
public class MailAccountRequest {

    @NotBlank(message = "is required")
    @Email(message = "must be an email address")
    @Size(max = 255, message = "must be at most 255 characters")
    private String emailAddress;

    @Size(max = 200, message = "must be at most 200 characters")
    private String displayName;

    @NotBlank(message = "is required")
    private String imapHost;

    @NotNull(message = "is required")
    @Min(value = 1, message = "must be a port number")
    @Max(value = 65535, message = "must be a port number")
    private Integer imapPort;

    private boolean imapSsl = true;

    @NotBlank(message = "is required")
    private String smtpHost;

    @NotNull(message = "is required")
    @Min(value = 1, message = "must be a port number")
    @Max(value = 65535, message = "must be a port number")
    private Integer smtpPort;

    /** Required the first time; blank on a later save keeps the stored one. */
    private String password;

    private boolean enabled = true;
}
