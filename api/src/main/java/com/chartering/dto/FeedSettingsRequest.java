package com.chartering.dto;

import lombok.Data;

/**
 * Every field optional, so the settings card and the prompt editor can each save their own part.
 * A blank prompt restores the default.
 */
@Data
public class FeedSettingsRequest {
    private Integer contextWindowTokens;
    private Integer summaryMaxTokens;
    private Integer notesMaxTokens;
    private Integer lookbackDays;
    private Integer maxCallsPerTopic;
    /** 0 turns the timer off; Fetch now still works. */
    private Integer fetchIntervalMinutes;
    private String systemPrompt;
    private String notesPrompt;
}
