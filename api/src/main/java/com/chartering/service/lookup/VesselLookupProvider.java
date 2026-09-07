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
     * <p>By name and nothing else, because that is what the caller has: this runs when the
     * IMO is missing, which is the only reason to be here at all. Narrowing the result is
     * the matcher's job, not the source's.
     *
     * <p>Returns an empty list when the source knows of no such ship — a real answer, since
     * much of this fleet is small tonnage a public database may simply not carry. Throws only
     * when the source could not be reached or could not be read, which is a fact about the
     * source rather than about the ship.
     */
    List<VesselParticulars> searchByName(String name) throws LookupException;

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
