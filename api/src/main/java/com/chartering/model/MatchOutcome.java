package com.chartering.model;

/**
 * What the broker did about one cargo/vessel pairing.
 *
 * <p>{@link #DISMISSED} is why {@link CargoVesselMatch} exists at all. Without somewhere to
 * record "not this ship for this cargo, stop showing me", the Match tab proposes the same
 * fifteen ships every morning — including the four already offered and the two the owner
 * turned down on Tuesday — and a screen meant to save work becomes work.
 */
public enum MatchOutcome {

    /** Worth working, not yet put to anyone. */
    SHORTLISTED,

    /** Put to the charterer or to the owner. */
    OFFERED,

    /** Somebody said no. */
    DECLINED,

    /** This is the one that fixed. */
    FIXED,

    /** Not suitable, whatever the score said. Hides the pair from the suggestions. */
    DISMISSED;

    /** Pairings that should stop appearing among fresh suggestions. */
    public boolean isClosed() {
        return this == DECLINED || this == DISMISSED || this == FIXED;
    }
}
