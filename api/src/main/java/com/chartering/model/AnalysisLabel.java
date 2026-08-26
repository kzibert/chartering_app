package com.chartering.model;

/**
 * What kind of email a training sample is.
 *
 * <p>The coarsest thing a model has to get right, and the first thing worth checking a
 * dataset for: a corpus that is nine parts cargo offers will read a position list as a
 * cargo offer. The Analysis tab counts by this for that reason.
 *
 * <p>Stored as its name, not its ordinal — a dataset outlives the order of a Java enum, and
 * a value read back as the wrong constant because somebody inserted one in the middle is
 * mislabelled training data, which is worse than none.
 */
public enum AnalysisLabel {

    /** Captured but not yet looked at. The state everything arrives in. */
    UNLABELLED,

    /** A cargo on offer: stem, load and discharge ranges, laycan, quantity, terms. */
    CARGO_OFFER,

    /** A vessel's open position: where she opens, when, and what she is. */
    VESSEL_OPENING,

    /**
     * Both, in one message. Not a hedge for "unsure" — a broker's daily circular routinely
     * carries a page of cargoes and a page of open tonnage, and forcing it into one of the
     * two would teach the model to ignore whichever half lost.
     */
    BOTH,

    /** Neither: a fixture report, a negotiation, an invoice, an out-of-office. */
    OTHER
}
