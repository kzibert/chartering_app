package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The Feed's settings with what each reset restores, so the screen can say so without asking twice. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedSettingsResponse(
        int contextWindowTokens,
        int summaryMaxTokens,
        int notesMaxTokens,
        int lookbackDays,
        int maxCallsPerTopic,
        int fetchIntervalMinutes,
        String systemPrompt,
        String notesPrompt,
        boolean systemPromptCustomised,
        boolean notesPromptCustomised,
        int defaultContextWindowTokens,
        int defaultSummaryMaxTokens,
        int defaultNotesMaxTokens,
        int defaultLookbackDays,
        int defaultMaxCallsPerTopic,
        int defaultFetchIntervalMinutes,
        String defaultSystemPrompt,
        String defaultNotesPrompt,
        List<String> placeholders,
        /** The endpoint in force. */
        String modelUrl,
        String modelName,
        /** True where a row holds it rather than the environment. */
        boolean modelUrlCustomised,
        boolean modelNameCustomised,
        /**
         * What clearing the field restores: {@code FEED_LLM_URL}, or the parser's endpoint where
         * that is blank — which is the single-server shape, reported as it is rather than as an
         * empty box that would look unconfigured.
         */
        String defaultModelUrl,
        String defaultModelName) {
}
