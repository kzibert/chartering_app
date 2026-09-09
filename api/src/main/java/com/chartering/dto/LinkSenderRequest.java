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

    /**
     * Which firm to attach, when the item has more than one behind it.
     *
     * <p>An item is one question about a hull however many emails raised it, so two brokers
     * can be sitting on the same one — and both are worth keeping, since who works her is
     * exactly what the record is for. Absent means the arrival that raised the item, which is
     * the only answer there was when every email had a row of its own.
     *
     * <p>It must be one of the item's own senders. This endpoint exists to record what an
     * email is evidence of; attaching a firm that has nothing to do with it is a decision
     * about the ship and belongs on her own record, where every link is in view.
     */
    private Long companyId;

    private String notes;
}
