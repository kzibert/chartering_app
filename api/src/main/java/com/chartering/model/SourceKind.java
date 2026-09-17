package com.chartering.model;

/**
 * How a record reached this desk.
 *
 * <p>Replaced a {@code from_mail} boolean when a third answer appeared, and the replacement
 * rather than an addition is the point: two booleans for one fact are two columns free to
 * disagree, and the one that had not been thought about is the one that ends up wrong.
 *
 * <p>Read as a fact about the <em>arrival</em>, not about the quality of what arrived. A
 * cargo somebody typed out of a phone call is {@code MANUAL} and is the best-checked row in
 * the table; one the sweep read off a board is {@code WEB} and nobody has looked at it yet.
 * The Cargoes tab's Source filter exists to tell exactly those two apart.
 */
public enum SourceKind {

    /** Typed on a form — including saved out of a paste somebody had just read. */
    MANUAL,

    /** Read out of a synced message by the parser sweep. */
    MAIL,

    /**
     * Read off a post on an outside board by the parser sweep.
     *
     * <p>The same circulars as {@code MAIL} and often literally the same text — a broker
     * mails his list to the desk and pastes it on ship.gr the same morning. Which door it
     * came in through is still worth keeping: nobody chose to send the board's copy here,
     * so it is the half of the intake with no relationship behind it.
     */
    WEB
}
