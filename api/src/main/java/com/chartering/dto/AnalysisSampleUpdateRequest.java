package com.chartering.dto;

import com.chartering.model.AnalysisLabel;
import com.chartering.model.AnalysisStatus;

/**
 * What a review changes: the two axes and the two texts. Never the email itself — a sample's
 * body is a snapshot of what arrived, and a corpus somebody has been editing the inputs of
 * is not a record of anything.
 */
public record AnalysisSampleUpdateRequest(
        AnalysisLabel label,
        AnalysisStatus status,
        /**
         * The target output. Must parse as JSON; the service refuses it otherwise, so a
         * broken line can never reach a training file. Send an empty string to clear it.
         */
        String annotation,
        String notes) {
}
