package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The WhatsApp settings in force, with the built-in default alongside so the screen can
 * show what Reset would restore without a second request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WhatsappSettingsResponse(
        /** Prefilled into the wa.me link. Supports the same {@code {{...}}} placeholders as a circular. */
        String message,
        /** What the message reverts to when the stored value is deleted. */
        String defaultMessage,
        /** true when the message differs from the built-in default. */
        boolean customised,
        /** Placeholder name → what it renders as, for the hint under the field. */
        Map<String, String> placeholders) {
}
