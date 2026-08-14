package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Exactly what one recipient received, reproduced by replaying the run's stored merge
 * against that recipient's stored fields. Both parts are included because that is what was
 * sent: every circular goes out as multipart/alternative, and the text part is what a
 * plain-text client displayed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationMessageResponse(
        String email,
        String personName,
        String subject,
        String html,
        String text) {
}
