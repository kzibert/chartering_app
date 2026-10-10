package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A question for the desk. Bounded, because it is sent to the model in full. */
public record AskRequest(
        @NotBlank(message = "The question is blank.")
        @Size(max = 1000, message = "The question is longer than 1,000 characters.")
        String question) {
}
