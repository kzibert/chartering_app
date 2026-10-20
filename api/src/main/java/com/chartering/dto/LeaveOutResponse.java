package com.chartering.dto;

/**
 * How many of the picked arrivals were actually taken off the list.
 *
 * <p>Counts rows created, not ids asked for: an arrival already read, already left out, or not
 * on this desk is not counted, so the number is what changed rather than what was requested.
 */
public record LeaveOutResponse(int leftOut) {
}
