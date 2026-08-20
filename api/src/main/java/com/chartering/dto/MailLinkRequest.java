package com.chartering.dto;

import lombok.Data;

/**
 * Attach a message to a company by hand, when the sender's address is not in the contacts.
 *
 * <p>All three ids are optional and all-null means "unlink". {@code createContact} is the
 * useful half: recording the address against the company as a contact is what makes the
 * <em>next</em> message from that sender link itself, so correcting one message can fix a
 * whole correspondence rather than just the row in front of you.
 */
@Data
public class MailLinkRequest {

    private Long companyId;

    /** Optional: the person at that company the address belongs to. */
    private Long personId;

    /**
     * Also add the sender's address to the contacts, under the company (and person) above,
     * so later mail from it links automatically. Ignored when the address is already there.
     */
    private boolean createContact = false;
}
