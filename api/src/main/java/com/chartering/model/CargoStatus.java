package com.chartering.model;

/**
 * How far a cargo has got.
 *
 * <p>Stored as its name in a varchar rather than a Postgres enum, so that adding a state is
 * a line here instead of a migration — this list will move while the desk works out what it
 * actually tracks.
 *
 * <p>{@link #isLive()} is the distinction the screens are built on: Match only proposes
 * tonnage against cargoes still worth working, and the Cargoes tab opens on the same set.
 */
public enum CargoStatus {

    /** Received, nothing offered yet. */
    OPEN,

    /** Tonnage has been put forward and we are waiting on the charterer. */
    QUOTED,

    /** On subs or in firm negotiation with one ship. */
    FIRM,

    /** Done, on another ship or on ours. Kept, because a fixture is the market history. */
    FIXED,

    /** Negotiated and failed. Distinct from FIXED: it says the cargo went nowhere here. */
    FAILED,

    /** The laycan has passed with nothing done. */
    EXPIRED,

    /** The charterer pulled it. */
    WITHDRAWN,

    /**
     * Not for this desk: the wrong trade, the wrong size, a charterer nobody here works with.
     *
     * <p>Distinct from WITHDRAWN (the charterer's decision) and FAILED (worked and went
     * nowhere) — this one is ours, made before any work. Kept rather than deleted, and that is
     * the whole value of it: the same enquiry comes round again next week from another broker,
     * and a deleted cargo would arrive as a new one, open and on Match, asking to be worked.
     * A cargo marked here is still there for the duplicate test to recognise — see
     * {@link #isRecognisedOnArrival()}.
     */
    NOT_WORKABLE;

    /** Still worth showing tonnage against. */
    public boolean isLive() {
        return this == OPEN || this == QUOTED || this == FIRM;
    }

    /**
     * Worth recognising when the same cargo arrives again.
     *
     * <p>The live ones, because a repeat is a second broker on an enquiry in hand. And
     * NOT_WORKABLE, because otherwise every cargo turned down comes back as a fresh OPEN one on
     * the next circular and the decision has to be made again. The closed ones are not: a
     * fixed or withdrawn cargo that turns up later is the market offering it again, which is a
     * new enquiry.
     */
    public boolean isRecognisedOnArrival() {
        return isLive() || this == NOT_WORKABLE;
    }
}
