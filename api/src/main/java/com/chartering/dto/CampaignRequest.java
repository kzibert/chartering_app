package com.chartering.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** A circular to send: one subject and body, delivered individually to every recipient. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CampaignRequest {

    @NotBlank(message = "subject is required")
    @Size(max = 300, message = "subject must be at most 300 characters")
    private String subject;

    /** HTML from the compose editor. A plain-text alternative is generated from it. */
    @NotBlank(message = "body is required")
    private String htmlBody;

    /**
     * Optional footer to append. Null means no footer at all — it does <em>not</em> fall back
     * to the default, so "send without a signature" stays expressible from the UI.
     */
    private Long footerId;

    @NotEmpty(message = "at least one recipient is required")
    @Valid
    private List<CampaignRecipientRequest> recipients;
}
