package com.chartering.service.lookup;

import java.util.List;

/**
 * Where particulars are looked up, behind one method.
 *
 * <p><b>A port, and it exists because the implementation behind it is the fragile part of
 * this feature.</b> {@link VesselFinderScraper} reads a public search page, which is a
 * arrangement that can stop working on any day somebody changes a stylesheet, and which the
 * site's terms do not invite. That was a deliberate choice made with the alternatives on the
 * table — the paid APIs cost between £100 and £700 a month — and the point of putting an
 * interface here is that the choice stays reversible: an API key arrives, one class is
 * written against it, one setting changes, and everything above this line is untouched.
 *
 * <p>Everything that matters — matching a candidate to a hull, proposing fields, recording
 * where a value came from, letting a person accept it field by field — lives above this
 * interface and is worth keeping whichever source is in force.
 */
public interface VesselLookupProvider {

    /** The name recorded against every lookup, so a stored row says which source answered. */
    String name();

    /**
     * Hulls answering to a name.
     *
     * <p>The fallback, and the harder question. It runs where no IMO is known on either side
     * — which is most of this mail, since a circular names a ship and almost never her number
     * — and a name is neither unique nor stable. Narrowing the result is the matcher's job,
     * not the source's.
     *
     * <p>Returns an empty list when the source knows of no such ship — a real answer, since
     * much of this fleet is small tonnage a public database may simply not carry. Throws only
     * when the source could not be reached or could not be read, which is a fact about the
     * source rather than about the ship.
     */
    List<VesselParticulars> searchByName(String name) throws LookupException;

    /**
     * The hull carrying an IMO number.
     *
     * <p><b>Asked first wherever a number is known, because it is the only question with one
     * answer.</b> An IMO is unique and survives a rename; a name is neither. Searching by name
     * for a hull whose number we hold means asking an ambiguous question and then reasoning
     * about the ambiguity — which is how a rename came back as "no such ship" and a common
     * name came back as three of them.
     *
     * <p><b>The implementation must return only hulls actually carrying the number.</b> Not a
     * politeness: a source that does not understand the question is far more dangerous than
     * one that answers it with nothing. VesselFinder ignores an {@code imo=} parameter
     * outright and serves an unfiltered page of twenty other ships, which would arrive here
     * looking exactly like results. Whoever implements this checks what came back.
     *
     * <p>Empty when the source has no such hull — an answer, since a public database may
     * simply not carry small tonnage. Throws only when it could not be reached or read.
     */
    List<VesselParticulars> searchByImo(String imo) throws LookupException;

    /** The source could not be reached, or answered something that could not be read. */
    class LookupException extends RuntimeException {
        public LookupException(String message) {
            super(message);
        }

        public LookupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
