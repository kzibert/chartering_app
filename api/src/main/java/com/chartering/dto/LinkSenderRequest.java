package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Attach the company that sent an email to the vessel it was about.
 *
 * <p>The case: a position list arrives from a broker who is not the owner on file. That the
 * broker is working this hull is a fact worth keeping — it is who to ring about her — and it
 * is not the same fact as ownership, which is why the capacity has to be chosen rather than
 * assumed. {@code owner} displaces the owner on the record; the two broker roles sit
 * alongside it.
 */
@Data
public class LinkSenderRequest {

    /** One of {@code owner}, {@code exclusive_broker}, {@code broker}. */
    @NotBlank(message = "Choose the capacity this company acts in.")
    private String role;

    private String notes;
}
