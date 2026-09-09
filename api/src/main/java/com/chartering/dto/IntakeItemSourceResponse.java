package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * One email that raised a review item, as the drawer lists it.
 *
 * <p>An item usually has one; a hull two brokers both carry, or one broker sends twice, has
 * several. The list is what lets a reviewer read each original in full and attach either firm
 * to the ship — the two things that were impossible while each arrival sat on a row of its own.
 *
 * @param mailMessageId the message to open. Null where the mailbox no longer holds it: the
 *                      sync mirrors a server whose folders get emptied, and that this arrival
 *                      happened outlives the copy of the email
 * @param current       whether the item's figures came from this arrival. The newest one wins
 *                      the payload on a merge, and the screen says which that was rather than
 *                      leaving the reader to compare timestamps
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntakeItemSourceResponse(
        Long id,
        Long parsedEmailId,
        Long mailMessageId,
        String mailSubject,
        String fromAddress,
        String fromName,
        Long senderCompanyId,
        String senderCompanyName,
        LocalDateTime receivedAt,
        OffsetDateTime reportedAt,
        boolean current) {
}
