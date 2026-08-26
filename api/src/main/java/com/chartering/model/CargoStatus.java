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
    WITHDRAWN;

    /** Still worth showing tonnage against. */
    public boolean isLive() {
        return this == OPEN || this == QUOTED || this == FIRM;
    }
}
