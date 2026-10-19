package com.chartering.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * How often the mailbox is read, how much of it at a time, and which model reads it.
 *
 * <p>Both fields are optional so the Settings tab can send one without holding the other,
 * matching how the rest of that screen behaves. The service validates the range and says
 * what it should be rather than clamping silently — a batch size quietly reduced from 500 to
 * 100 is a setting that looks saved and is not.
 */
@Data
public class ParserSettingsRequest {

    /** Minutes between sweeps. 0 turns the timer off; "Parse now" still works. */
    private Integer sweepIntervalMinutes;

    /** How many messages one sweep reads before stopping. */
    private Integer sweepBatchSize;

    /**
     * How far back unparsed mail may be fetched from, in days. 0 means no limit.
     *
     * <p>What stops the first run reading a mailbox's whole history. A three-year-old
     * position list is not information — the ship sailed — so parsing it spends GPU to put
     * rows on Open Fleet that are wrong by construction.
     */
    private Integer sweepMaxAgeDays;

    /**
     * The chat-completions endpoint. Blank restores {@code PARSER_URL}.
     *
     * <p>Here rather than only in the environment because the reason to change it is not a
     * redeploy: the extraction finetune and the Feed's general model are swapped on one card,
     * and a second box gets a port of its own. Refused rather than clamped if it is not an
     * http(s) URL with a host — a typo saved here is a sweep that fails from inside the HTTP
     * client on every message afterwards.
     */
    private String modelUrl;

    /**
     * The model name to send, or blank for {@code PARSER_MODEL}.
     *
     * <p>llama-server serves one model and ignores it; an Ollama refuses the request without
     * one, so pointing this at an Ollama is the address and this field and nothing else.
     */
    private String modelName;

    /**
     * Worked examples per email, 0 to 8. Absent leaves the setting alone.
     *
     * <p>Installation-wide, so only a platform administrator can change it; a desk administrator
     * sending it gets the refusal the endpoint keys give.
     */
    private Integer fewShotExamples;

    /** The character budget for those examples, 1,000 to 60,000. Absent leaves it alone. */
    private Integer fewShotMaxChars;

    /**
     * The hosted model's chat-completions endpoint, used under {@code PARSER_PROVIDER=CLOUD}. Blank
     * restores {@code PARSER_CLOUD_URL}. Refused unless it is an http(s) URL with a host, as
     * {@link #modelUrl} is.
     */
    private String cloudUrl;

    /** The hosted model's name, sent on every request. Blank restores {@code PARSER_CLOUD_MODEL}. */
    private String cloudModel;

    /**
     * A new API key for the hosted provider. Absent or blank leaves the stored key alone.
     *
     * <p>Write-only: the response carries its last four characters and never the key itself. Stored
     * encrypted, and refused on a deployment without {@code CREDENTIALS_KEY}.
     */
    @Size(max = 500, message = "The API key is longer than 500 characters.")
    private String cloudApiKey;

    /** True removes the stored API key. Cannot be combined with a new key in the same request. */
    private Boolean clearCloudApiKey;
}
