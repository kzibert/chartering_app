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
        String defaultModelName,
        /**
         * Worked examples per email in force. 0 means the few-shot experiment is off, which is
         * the default and what every installation runs until the harness has scored it.
         */
        int fewShotExamples,
        /** The character budget for those examples. */
        int fewShotMaxChars,
        int defaultFewShotExamples,
        int defaultFewShotMaxChars,
        /** "LOCAL" or "CLOUD": which side {@code PARSER_PROVIDER} puts this deployment on. */
        String provider,
        /** The hosted endpoint in force. Only meaningful to the screen under CLOUD. */
        String cloudUrl,
        String cloudModel,
        boolean cloudUrlCustomised,
        boolean cloudModelCustomised,
        /** {@code PARSER_CLOUD_URL}, which is what clearing the field restores. */
        String defaultCloudUrl,
        /** {@code PARSER_CLOUD_MODEL}. */
        String defaultCloudModel,
        /**
         * True when a usable key is stored: present and decryptable, the same test the parser applies.
         * A key that cannot be read reads as not set, so the screen asks for it again.
         */
        boolean cloudApiKeySet,
        /** The last four characters of the stored key, for recognising it. Never the key. */
        String cloudApiKeyHint,
        /** Whether this deployment can store a key at all, which is whether CREDENTIALS_KEY is set. */
        boolean cloudApiKeyStorable) {
}
