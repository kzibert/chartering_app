package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The greeting prefilled into a wa.me link, as set from the Settings tab. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WhatsappSettingsRequest {

    /**
     * Capped well below what wa.me will carry — the whole thing ends up percent-encoded in
     * a URL, and a greeting long enough to hit this is not a greeting.
     */
    @NotBlank(message = "a message is required")
    @Size(max = 500, message = "the message must be 500 characters or fewer")
    private String message;
}
