package com.chartering.dto;

/**
 * Where an address came from, as the People tab's Source filter asks it.
 *
 * <p>Three answers, and they are the three doors data comes in through. They are stored as
 * two independent booleans on the contact rather than as this enum ({@code is_legacy} and
 * {@code from_file}), because the columns predate the question: {@code is_legacy} is on
 * four tables and read by the vessel and company searches too, and rewriting all of it to
 * hold a source string would change three screens to answer one.
 *
 * <p>Sent by name, so a typo in the query string is a 400 rather than a filter that quietly
 * matched everything.
 */
public enum ContactSource {

    /** Typed into the app - not carried over, not imported from a file. */
    APP,

    /** Carried over from the old database at the baseline import. */
    LEGACY,

    /**
     * Arrived through the contacts-file importer. Never also LEGACY: the importer writes
     * new data whichever file it read, which is why these are two questions and not one.
     */
    FILE
}
