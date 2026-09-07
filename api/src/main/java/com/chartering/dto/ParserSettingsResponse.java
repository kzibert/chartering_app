package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The parser's runtime settings, with what "reset" would restore.
 *
 * <p>The defaults travel with the values for the reason the circulation settings do: a
 * screen offering "reset to defaults" has to be able to say what they are, and a second call
 * to find out is a second call that can disagree with the first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParserSettingsResponse(
        int sweepIntervalMinutes,
        int sweepBatchSize,
        /** Days. 0 = no limit. */
        int sweepMaxAgeDays,
        int defaultSweepIntervalMinutes,
        int defaultSweepBatchSize,
        int defaultSweepMaxAgeDays) {
}
