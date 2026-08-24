package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ContactRequest {

    /**
     * Who this address/number belongs to, or null for one that belongs to the company
     * itself — a {@code chartering@} or {@code ops@} desk rather than a person. At least
     * one of this and {@link #companyId} must be set; a contact filed under neither
     * belongs to nothing and can be found from nowhere.
     */
    private Long personId;

    private Long companyId;

    /**
     * This address's own greeting, overriding the person's. Blank means "use the person's,
     * or the general greeting when there is no person" — which is the default a company-wide
     * address should have.
     */
    @Size(max = 120, message = "greetingName must be at most 120 characters")
    private String greetingName;

    @NotBlank(message = "contactKind is required")
    @Pattern(regexp = "email|phone", message = "contactKind must be 'email' or 'phone'")
    private String contactKind;

    @NotBlank(message = "contactValue is required")
    private String contactValue;

    /**
     * Work / Mobile / Direct / Fax / Home / Other. Phones only — an email has no
     * equivalent. Free text on purpose: an unrecognised word from an import is a label
     * worth keeping, not a request worth refusing.
     */
    @Size(max = 20, message = "label must be at most 20 characters")
    private String label;

    private String notes;
}
