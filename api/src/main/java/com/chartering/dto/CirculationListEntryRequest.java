package com.chartering.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row written to a list. Only the address is required — a missing merge field falls
 * back to neutral wording at send time rather than rendering an empty gap.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CirculationListEntryRequest {

    @NotBlank(message = "email is required")
    @Email(message = "not a valid email address")
    private String email;

    /** The contact this came from, when it came from one. Hand-typed rows leave it null. */
    private Long contactId;

    private Long personId;
    private String personName;
    private String greetingName;
    private String title;
    private Long companyId;
    private String companyName;
}
