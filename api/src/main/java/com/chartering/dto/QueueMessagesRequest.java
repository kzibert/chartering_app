package com.chartering.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The messages a person picked on the Mailbox tab, to add to the waiting list.
 *
 * <p>The same ids and the same cap as {@link ParseMessagesRequest}, for the same reasons: an id from
 * another desk is not found here and is passed over, and two hundred is a request-size bound.
 *
 * <p>{@code reread} is for a message already read. Without it such a message is skipped, which is
 * what a first press means. With it the message is queued again and its new reading replaces the old
 * one on the same parse row, which is how a person asks for a second look after the model or the
 * prompt has changed. A missing field is {@code false}, so a client that never sends it behaves as
 * it always has.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueueMessagesRequest {

    @NotEmpty(message = "Pick at least one message to read.")
    @Size(max = 200, message = "Read at most 200 messages in one go.")
    private List<@NotNull(message = "A message id is required.") Long> mailMessageIds;

    private boolean reread;
}
