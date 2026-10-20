package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What "Add to waiting list" did with the messages a person picked.
 *
 * <p>{@code queued} counts the messages the sweep will now read, including any that were already
 * queued, so pressing the button twice gives the same figure. {@code skipped} counts what was passed
 * over: already read, no text body to read, or not on this desk. The two add up to the distinct ids
 * sent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueMessagesResponse(int queued, int skipped) {
}
