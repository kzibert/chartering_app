package com.chartering.model;

/**
 * How reading one email went.
 *
 * <p>All three are terminal for the sweep's purposes — a message with a row of any of these
 * is not picked up again — but only {@link #FAILED} is ever retried, and only while its
 * attempt count is under the configured ceiling. That distinction is the whole reason
 * {@link #SKIPPED} exists as a state of its own rather than as a failure: an attachment-only
 * position list has nothing to send a model, and retrying it hourly for the rest of its life
 * would be a cost with no possible outcome.
 */
public enum ParseStatus {

    /** The model answered and the answer parsed. */
    PARSED,

    /** The server was unreachable, timed out, or answered something that is not JSON. */
    FAILED,

    /** Nothing to read — no text body at all. */
    SKIPPED
}
