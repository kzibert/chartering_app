package com.chartering.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The messages a person picked on the Mailbox tab, to be read now.
 *
 * <p>Ids of this desk's own messages. An id from another desk is not an error here: it is not
 * found on this desk and is counted as skipped, the same as a message already read, so the
 * request reveals nothing about whether another desk holds it.
 *
 * <p>The cap is a request-size bound, not a model budget: a picked list of two hundred is already
 * a run of two hundred model calls, and a larger one is more likely a mistake than a choice.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParseMessagesRequest {

    @NotEmpty(message = "Pick at least one message to read.")
    @Size(max = 200, message = "Read at most 200 messages in one go.")
    private List<@NotNull(message = "A message id is required.") Long> mailMessageIds;
}
