package com.chartering.model;

/**
 * What became of a reported opening position.
 *
 * <p>{@link #SUPERSEDED} is the one worth explaining: it is what a newer report about the
 * same vessel does to an older one, and it is a status rather than a delete because "she
 * was said to be open Adriatic on the 2nd and then wasn't" is a thing a broker looks back
 * at. Nothing in this feature deletes a position.
 */
public enum PositionStatus {

    /** Current, and the only status the Open Fleet tab and Match read by default. */
    LIVE,

    /** She fixed. The position is history the moment this is set. */
    FIXED,

    /** Pulled by whoever reported it. */
    WITHDRAWN,

    /** A newer report about the same vessel from the same source replaced it. */
    SUPERSEDED,

    /** The open dates have passed with nothing said since. */
    EXPIRED;

    public boolean isLive() {
        return this == LIVE;
    }
}
