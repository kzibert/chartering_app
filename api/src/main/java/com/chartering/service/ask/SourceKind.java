package com.chartering.service.ask;

/**
 * What a chunk was made from. The name is what {@code search_chunks.source_kind} stores, so a new
 * kind is a line here and nothing in the schema.
 *
 * <p>{@code MAIL} is a message in a mailbox, {@code POST} a post from a board the desk reads into
 * Intake, {@code CARGO} an enquiry on the Cargoes tab, and {@code POSITION} a vessel's open report.
 * Mail is the only kind with an owner, and the only one whose visibility is narrower than the desk.
 */
public enum SourceKind {
    MAIL,
    POST,
    CARGO,
    POSITION;

    /**
     * Whether a record of this kind is kept as one chunk. Cargoes and positions are a few lines
     * each, and a split would answer a question about one from half of it; mail and posts are long
     * and are split.
     */
    public boolean isWhole() {
        return this == CARGO || this == POSITION;
    }
}
