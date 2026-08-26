package com.chartering.model;

/**
 * Whether a sample is fit to train on.
 *
 * <p>A second axis rather than a finer {@link AnalysisLabel}: knowing what an email
 * <em>is</em> and knowing whether this particular example belongs in a dataset are separate
 * judgements, and a labelled example can still be a bad one — truncated, duplicated in
 * substance, or annotated by somebody who was guessing.
 */
public enum AnalysisStatus {

    /** Captured, not reviewed. */
    NEW,

    /**
     * A human has said this example is right. The only status the export reads — an
     * unreviewed sample never reaches a training file by accident, which is the whole point
     * of the status existing.
     */
    READY,

    /**
     * Reviewed and rejected. Kept rather than deleted, so the next capture over the same
     * folder does not bring the same junk back to be judged again.
     */
    SKIPPED
}
