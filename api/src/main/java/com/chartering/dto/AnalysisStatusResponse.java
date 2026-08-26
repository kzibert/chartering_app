package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Whether the analysis workbench is part of this deployment, and how far the corpus has got.
 *
 * <p><b>This endpoint answers even when the feature is off</b> — it is the one that does.
 * Everything else behind the feature 404s, and the UI needs a truthful "no" it can act on:
 * that answer is what removes the tab from the navigation, rather than leaving a tab that
 * only produces errors when clicked.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisStatusResponse(
        /** ANALYSIS_ENABLED. False on the hosted deployment, by design. */
        boolean enabled,
        /** Everything below is null when disabled: nothing was counted, nothing is claimed. */
        Long totalSamples,
        /** Ready to export — what a training file would actually contain today. */
        Long readySamples,
        /** Rows per label, keyed by the enum name. Absent labels mean none of that kind. */
        Map<String, Long> byLabel,
        Map<String, Long> byStatus,
        /**
         * A starting shape for each label's annotation, so a corpus is annotated
         * consistently instead of one reviewer's JSON at a time. Suggestions the review form
         * prefills with, not a schema the server enforces.
         */
        Map<String, String> annotationTemplates,
        /** The system prompt every exported example is trained against. */
        String exportSystemPrompt,
        Integer maxBodyChars,
        Integer maxCapturePerRun,
        /**
         * Whether there is any synced mail to capture from, and how much. A corpus cannot be
         * built out of an empty mailbox, and saying so is more use than an empty capture run.
         */
        Boolean mailboxEnabled,
        Long syncedMessages,
        /** What is missing before this is usable, in words. Empty when nothing is. */
        List<String> warnings) {
}
