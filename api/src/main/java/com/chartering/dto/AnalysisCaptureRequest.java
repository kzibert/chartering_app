package com.chartering.dto;

import java.time.LocalDateTime;

/**
 * Which synced mail to take into the corpus.
 *
 * <p>The same axes the Mailbox tab filters on, and on purpose: the useful capture is almost
 * never "everything" but "the Brokers folder, last quarter" or "everything from
 * @some-broker.com" — the shape of a corpus is decided by what is asked for here, and asking
 * for it in the vocabulary the user already filters mail with is what makes that possible
 * without a second query language.
 *
 * <p>Every field is optional. All of them unset means the whole mailbox, bounded by the
 * per-run cap.
 */
public record AnalysisCaptureRequest(
        /** A folder on the mail server, and everything nested under it. */
        String imapFolder,
        /** One of the app's own folders — the other filing axis, as in the Mailbox tab. */
        Long folderId,
        /** Free text over sender, subject and recipients. */
        String search,
        /** Also scan message bodies. Slow, exactly as in the mailbox — deliberately opt-in. */
        Boolean searchBody,
        LocalDateTime receivedFrom,
        LocalDateTime receivedTo,
        /** Cap for this run. Clamped to the configured maximum; omitted means that maximum. */
        Integer limit) {
}
