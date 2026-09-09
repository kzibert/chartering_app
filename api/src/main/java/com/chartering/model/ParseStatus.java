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
    SKIPPED,

    /**
     * A person has taken this message out of the parser's hands.
     *
     * <p>The other half of a failure somebody has actually looked at. {@link #FAILED} is a
     * standing question — try again, the box may have been asleep — and there has to be an
     * answer to it that is not "retry for ever" or "delete the row and let tomorrow's sweep
     * find it again". The email that defeats the model every time, the forwarded thread with
     * no position in it, the newsletter: recorded as ignored, out of the retry queue, still
     * on the Log where it can be reopened if the judgement was wrong.
     */
    IGNORED
}
