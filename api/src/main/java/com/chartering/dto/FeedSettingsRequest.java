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

    /**
     * The chat-completions endpoint summaries go to. Blank restores {@code FEED_LLM_URL}, and
     * where that is blank too, the parser's own endpoint.
     *
     * <p>The setting this side most needs: an 8GB card cannot hold the extraction finetune and a
     * general instruct model at once, so the two servers are swapped, and which port is up is
     * not a thing to redeploy for.
     */
    private String modelUrl;

    /** The model name to send with them, or blank for {@code FEED_LLM_MODEL}. */
    private String modelName;
}
