package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The arrivals a person has taken off the waiting list before the sweep reads them.
 *
 * <p>Two lists because the two doors have two id spaces: a mail message and a board post can
 * share a number and mean different things. Ids of this desk's own arrivals; an id from another
 * desk is not an error, it is simply not found and is skipped, the same as on
 * {@link ParseMessagesRequest}, so the request reveals nothing about whether another desk holds it.
 *
 * <p>The caps are request-size bounds, not budgets: leaving out is a row write with no model
 * call behind it, so the cap only has to stop a mistaken request from being a bulk operation by
 * accident.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaveOutRequest {

    @Size(max = 500, message = "Leave out at most 500 messages in one go.")
    private List<@NotNull(message = "A message id is required.") Long> mailMessageIds;

    @Size(max = 500, message = "Leave out at most 500 board posts in one go.")
    private List<@NotNull(message = "A board post id is required.") Long> feedItemIds;

    @Size(max = 500, message = "Keep the note under 500 characters.")
    private String note;

    /**
     * Nothing picked is a mistake, not an empty success: an empty request would otherwise answer
     * "left out 0" and look like the feature working.
     */
    @JsonIgnore
    @AssertTrue(message = "Pick at least one message or board post to leave out.")
    public boolean isAnyPicked() {
        return (mailMessageIds != null && !mailMessageIds.isEmpty())
                || (feedItemIds != null && !feedItemIds.isEmpty());
    }
}
