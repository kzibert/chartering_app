package com.chartering.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Why a message is being taken out of the parser's hands.
 *
 * <p>Optional, and it is worth it being optional: most of what lands here is obvious from the
 * subject and nobody should have to type "newsletter" to be rid of it. Where somebody does
 * explain — "position list is in the attachment", "same thread, already read" — that note is
 * the only thing on the row that says why a person, rather than the model, decided this.
 */
@Data
public class IgnoreRequest {

    @Size(max = 500, message = "Keep the note under 500 characters.")
    private String note;
}
