package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * What "Add to waiting list" did with the messages a person picked.
 *
 * <p>{@code queued} counts the messages the sweep will now read, including any that were already
 * queued, so pressing the button twice gives the same figure. A message already read is counted here
 * only when the request asked for a reread ({@code REREAD}); otherwise it is skipped. {@code skipped}
 * counts what was passed over: already read, no text body to read, or not on this desk. The two add
 * up to the distinct ids sent.
 *
 * <p>{@code messages} says what happened to each of them: one entry per distinct id sent, in the
 * order sent. An id not on this desk has no subject or sender to show, and those fields are left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueMessagesResponse(int queued, int skipped, List<Message> messages) {

    /**
     * One picked message's outcome. {@code outcome} is one of QUEUED, REQUEUED, ALREADY_QUEUED,
     * REREAD, ALREADY_READ, NO_TEXT or NOT_FOUND. {@code parseStatus} and {@code parsedAt} are the
     * existing row's, and are present only for ALREADY_READ, so the person can see when it was read and
     * how. A REREAD entry carries neither: its row has just been turned back to queued, so the old
     * reading's time and status would describe a row that no longer stands for it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(Long mailMessageId, String subject, String fromAddress, String outcome,
                          String parseStatus, OffsetDateTime parsedAt) {
    }
}
