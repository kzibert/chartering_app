package com.chartering.service.ask;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One record of the desk's text, read and rendered for indexing: what it is, whose it is, and the
 * text a question can be answered from.
 *
 * @param header    the lines every chunk of this record carries (subject, sender, date; the board;
 *                  the vessel and her position). Not searched on its own; prepended to each chunk.
 * @param body      the record's own text, as stored
 * @param date      the date shown beside it, or null where the record has none
 * @param updatedAt the record's own change time, for CARGO and POSITION; null for the immutable kinds
 */
public record SearchDocument(SourceKind kind, long sourceId, Long ownerUserId, String title,
                             LocalDateTime date, OffsetDateTime updatedAt, String header, String body) {

    /** The chunks this record is kept as, each beginning with the header. */
    public List<String> chunks() {
        if (kind.isWhole()) {
            return List.of(TextChunker.whole(header, body));
        }
        return TextChunker.chunks(header, body);
    }
}
