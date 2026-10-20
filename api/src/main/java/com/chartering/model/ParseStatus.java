package com.chartering.model;

/**
 * How reading one email went.
 *
 * <p>{@link #PARSED}, {@link #SKIPPED} and {@link #IGNORED} are terminal for the sweep's purposes
 * — a message with a row of any of these is not picked up again — and only {@link #FAILED} is ever
 * retried, and only while its attempt count is under the configured ceiling. That distinction is the
 * whole reason {@link #SKIPPED} exists as a state of its own rather than as a failure: an
 * attachment-only position list has nothing to send a model, and retrying it hourly for the rest of
 * its life would be a cost with no possible outcome.
 *
 * <p>{@link #QUEUED} is the one state that is not terminal and not a result: it is a standing request
 * from a person, and the sweep takes it on its next pass whatever the message's age. It is not in the
 * retry queue, and it is not something the Log lists as a parse outcome.
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
    IGNORED,

    /**
     * A person asked for this message to be read, and the sweep has not read it yet.
     *
     * <p>Written by "Add to waiting list" on the Mailbox tab. A queued message is read whatever its
     * age, ahead of the ordinary arrivals, because the person has already decided it is worth a
     * model call — the lookback exists to stop a timer spending GPU on old mail nobody chose, and
     * this is somebody choosing. Attempts are zero and the error is empty until the sweep takes it,
     * so the row reads as a request rather than a failure. Leaving it out turns it into
     * {@link #IGNORED}; reading it turns it into an ordinary result.
     */
    QUEUED
}
