package com.chartering.dto;

import java.util.List;

/**
 * What one capture run did.
 *
 * <p>Every number here answers a question somebody asks straight afterwards, which is why
 * "captured" alone is not enough: a run that reports 0 captured out of 400 matched has
 * worked perfectly (the folder is already in the corpus) or has done nothing useful (every
 * message was empty), and only the breakdown tells those apart.
 */
public record AnalysisCaptureResponse(
        /** Messages the filter matched, before anything was skipped. */
        long matched,
        /** Rows written. */
        int captured,
        /** Already in the corpus — the dedupe doing its job on a re-run. */
        int alreadyPresent,
        /** No usable text: an empty body, or a message that was only an attachment. */
        int skippedEmpty,
        /** True when the run stopped at its cap rather than at the end of the matches. */
        boolean limitReached,
        /** A few subjects that were taken, so the user can see what they just asked for. */
        List<String> examples) {
}
