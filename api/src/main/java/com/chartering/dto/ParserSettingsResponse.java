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
        int defaultSweepMaxAgeDays,
        /** The endpoint in force, which is the stored one where there is one. */
        String modelUrl,
        /** Blank means no model name is sent, which is what llama-server wants. */
        String modelName,
        /**
         * True where a row holds it rather than the environment.
         *
         * <p>Sent so the screen can say which of the two is answering. A value stored equal to
         * the configured one is deleted rather than kept, so this is never true of an address
         * that merely looks the same.
         */
        boolean modelUrlCustomised,
        boolean modelNameCustomised,
        /** {@code PARSER_URL}, which is what clearing the field restores. */
        String defaultModelUrl,
        /** {@code PARSER_MODEL}, normally blank. */
        String defaultModelName) {
}
