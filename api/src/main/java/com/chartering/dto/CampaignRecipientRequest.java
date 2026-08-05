package com.chartering.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the email list, carrying the mail-merge fields the UI already keeps
 * per contact. Everything except the address is optional — placeholders for missing
 * fields fall back to a neutral wording rather than rendering an empty gap.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CampaignRecipientRequest {

    @NotBlank(message = "recipient email is required")
    @Email(message = "not a valid email address")
    private String email;

    private Long contactId;
    private String greetingName;
    private String personName;
    private String title;
    private String companyName;
}
