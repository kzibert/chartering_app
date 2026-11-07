package com.chartering.dto;

/**
 * How many of the picked arrivals were actually taken off the list.
 *
 * <p>Counts arrivals taken off the list, not ids asked for: an arrival already read, already left
 * out, or not on this desk is not counted, so the number is what changed rather than what was
 * requested. A queued arrival is counted, because leaving it out is a change.
 */
public record LeaveOutResponse(int leftOut) {
}
