package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One sample opened for review: the row, the email text, and the annotation being written
 * against it.
 *
 * <p>Composed rather than repeated — the list row and the opened sample must never disagree
 * about a label, and the surest way to guarantee that is for there to be one definition of
 * what a row is.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisSampleDetailResponse(
        AnalysisSampleResponse sample,
        /** The snapshot that is trained on. Plain text; there is no HTML part by design. */
        String bodyText,
        /** The target output, as stored. Valid JSON or absent — never half-written. */
        String annotation) {
}
