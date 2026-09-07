package com.chartering.model;

/**
 * Where a review item has got to.
 *
 * <p>Resolved items are kept rather than deleted, for the reason {@code DISMISSED} exists on
 * a match: "I looked at this and said no" is an answer, and a queue that forgets its answers
 * asks the same question again on the next circular.
 */
public enum IntakeItemStatus {

    PENDING,

    /** Applied — the vessel created, the fields written, the cargoes merged. */
    ACCEPTED,

    /** Looked at and declined. Nothing was written. */
    REJECTED
}
