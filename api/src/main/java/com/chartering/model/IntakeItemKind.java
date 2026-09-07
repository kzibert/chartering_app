package com.chartering.model;

/**
 * The three questions a parse can leave behind.
 *
 * <p>What they have in common is what defines the set: each one would <em>change</em>
 * something a person put on file. Everything a parse can do that only appends — a position
 * for a known hull, a cargo nothing else looks like — is applied without asking, because the
 * worst case there is a row that gets superseded or deleted, and the cost of stopping for
 * each of eighty positions in one circular is that nobody reads the queue at all.
 */
public enum IntakeItemKind {

    /**
     * A position naming a hull with no match on name, former name or IMO.
     *
     * <p>Accepting creates the vessel from what the email said and files her position.
     * Pointing it at an existing vessel instead is the other half of the same answer, and it
     * is what a rename needs: the hull is on file under the name she carried last year.
     */
    NEW_VESSEL,

    /**
     * She is on file and the email disagrees about her particulars.
     *
     * <p>One item per vessel per email, listing every field that differs, so accepting is
     * one decision over a table rather than one per column. Her position has already been
     * filed by the time this is raised — where she is open is not in dispute, only what she
     * is.
     */
    VESSEL_FIELDS,

    /**
     * A cargo that looks like one already in hand.
     *
     * <p>Normal rather than exceptional: a charterer works several brokers and this desk
     * hears the same enquiry three times in a morning. Merging keeps one cargo and both
     * senders; keeping it separate is a real answer too, because two firms working what
     * reads like one cargo sometimes are not.
     */
    CARGO_MERGE
}
