package com.chartering.dto;

import java.time.LocalDateTime;

/**
 * Which circulars to take into the corpus, out of the mailbox or off the boards.
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
        /**
         * {@code MAILBOX} (the default) or {@code WEB}.
         *
         * <p>One request rather than two endpoints because it is one decision — what goes in
         * the corpus — asked on one screen. The fields below split by which half they serve,
         * and the ones that do not apply are ignored rather than refused: a folder means
         * nothing to a board, and a request that carried one is a stale form, not an error
         * worth stopping a capture over.
         */
        String source,
        /**
         * Which board, for a {@code WEB} capture.
         *
         * <p>Blank takes every source marked <i>Read into Intake</i> rather than every source
         * there is, and that is deliberate: those are the boards carrying circulars, which is
         * what this corpus is about. A trade-press feed would contribute articles a
         * cargo-extraction model has nothing to learn from, and five hundred of them would
         * have to be marked SKIPPED by hand.
         */
        Long feedSourceId,
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
