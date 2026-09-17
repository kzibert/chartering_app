package com.chartering.model;

/**
 * The four questions a parse can leave behind.
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
    CARGO_MERGE,

    /**
     * The firm that signed the circular, set against the firm on file.
     *
     * <p>Every circular ends in a full style — the name, the address, the site, the people
     * and their numbers — and it is the one part of an email that is <em>about</em> the
     * sender rather than about the market. The database goes stale in exactly that place
     * while the market keeps posting its current details through the door: a broker moves
     * office, a desk address changes, a new charterer joins the list.
     *
     * <p>Raised when the signature names a firm nothing here matches, or matches one whose
     * record it disagrees with or could fill gaps in. Accepting creates or updates only what
     * a person ticked — a signature is a lead sheet, not a source of record, which is the
     * rule the contacts importer already states. Nothing arrives flagged main or for
     * circulation: eighty addresses arriving ready to be circulated is one send away from a
     * bounce storm.
     *
     * <p><b>One pending item per firm, however many circulars they send.</b> The others
     * suppress to keep a queue readable; this one would be unusable without it, because a
     * signature arrives with every list rather than only when something is wrong.
     */
    COMPANY_DETAILS
}
