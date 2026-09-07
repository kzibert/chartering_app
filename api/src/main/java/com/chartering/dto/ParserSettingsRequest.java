package com.chartering.dto;

import lombok.Data;

/**
 * How often the mailbox is read, and how much of it at a time.
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
}
