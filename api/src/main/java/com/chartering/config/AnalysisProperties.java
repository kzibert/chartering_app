package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The email-analysis feature: keeping incoming mail as training data for a model that reads
 * cargo offers and vessel opening positions.
 *
 * <p><b>Off by default, and off on the hosted deployment on purpose.</b> This is a workbench,
 * not a desk tool — it accumulates a corpus, it is worked through in long sessions, and what
 * comes out of it is a file that goes somewhere else entirely. None of that wants to happen
 * on a free instance that sleeps after fifteen minutes, and a half-labelled corpus on a
 * server nobody is sitting at is a liability rather than an asset. {@code ANALYSIS_ENABLED}
 * is true in the local compose environment and explicitly false in {@code render.yaml}.
 *
 * <p>The switch is runtime, not build-time: the schema is the same everywhere (Flyway builds
 * one schema, not one per deployment), the code ships everywhere, and turning it on is one
 * environment variable and a restart. What "off" buys is that the tab is not in the
 * navigation and every endpoint behind it answers 404 — see {@code AnalysisService}.
 */
@Component
@ConfigurationProperties(prefix = "chartering.analysis")
@Data
public class AnalysisProperties {

    /** Master switch. Off means the feature is not part of this deployment at all. */
    private boolean enabled = false;

    /**
     * How much of a body is kept per sample.
     *
     * <p>A cap, not a target. The thing being learned is at the top of a broker's circular;
     * what runs past 20,000 characters is a quoted chain, a disclaimer block and somebody's
     * signature image rendered as text. Keeping all of it would spend the model's context on
     * material that teaches it nothing, and would make one 400KB Outlook thread the largest
     * training example in the set.
     */
    private int maxBodyChars = 20_000;

    /**
     * The most messages one capture run will take.
     *
     * <p>Capture is a synchronous request that reads bodies out of {@code mail_messages} and
     * writes a row each, so the bound is what keeps "capture everything" from being one
     * transaction over a mailbox with fifteen years in it. Reaching it is not an error: the
     * run reports how many it took and how many matched, and running it again continues —
     * the dedupe means a second pass starts where the first stopped.
     */
    private int maxCapturePerRun = 500;
}
