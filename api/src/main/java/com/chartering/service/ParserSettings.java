package com.chartering.service;

import com.chartering.config.ParserProperties;
import com.chartering.model.AppSetting;
import com.chartering.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * How often the mailbox is read, and how much of it at a time.
 *
 * <p><b>Runtime settings rather than environment variables, and the reason is the same one
 * that put the circular provider in this table.</b> These are knobs turned while watching the
 * queue — "that was too slow this morning", "stop reading for a bit, the GPU is training" —
 * and an environment variable is a redeploy. How long to wait for the model stays in
 * {@code ParserProperties}, because a timeout is a fact about a deployment rather than about a
 * working day.
 *
 * <p><b>Where the model lives is both.</b> {@code PARSER_URL} is still what a fresh install
 * uses, and {@link #endpoint()} lets it be overridden from the Settings tab — because the
 * reason to move it is not a redeploy either: the extraction finetune and the general model are
 * swapped on one card, a second box gets a port of its own, an Ollama is tried for an evening.
 * See {@link ModelEndpoint} for why an override that equals the configured value is deleted
 * rather than stored.
 *
 * <p>Only overridden values are stored; an absent row means the default below is in force.
 * That is this table's standing rule and it is what makes "reset" a delete rather than a
 * write.
 *
 * <p>Kept apart from {@link SettingsService} rather than added to it: that class is about
 * sending circulars, holds a provider's worth of pacing keys and captures the mail sender's
 * configured defaults at startup. Parsing shares none of that, and the two would only be one
 * class because they are both "settings".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParserSettings {

    /** Minutes between sweeps. 0 turns the timer off; the button still works. */
    public static final String SWEEP_INTERVAL_MINUTES = "parser.sweepIntervalMinutes";

    /** How many messages one sweep reads. */
    public static final String SWEEP_BATCH_SIZE = "parser.sweepBatchSize";

    /** How far back unparsed mail may be fetched from. 0 means no limit. */
    public static final String SWEEP_MAX_AGE_DAYS = "parser.sweepMaxAgeDays";

    /** The chat-completions endpoint. Absent means {@code PARSER_URL}. */
    public static final String MODEL_URL = "parser.modelUrl";

    /** The model name to send. Absent means {@code PARSER_MODEL}, which is normally nothing. */
    public static final String MODEL_NAME = "parser.modelName";

    private static final List<String> MODEL_KEYS = List.of(MODEL_URL, MODEL_NAME);

    /**
     * Thirty minutes.
     *
     * <p>Chosen against the mailbox poller's five: a circular that arrives at 09:02 is worth
     * having by 09:30, and reading is minutes of a GPU rather than seconds of a network
     * socket. Anything much shorter mostly wakes up to find nothing new.
     */
    public static final int DEFAULT_INTERVAL_MINUTES = 30;

    /**
     * Twenty messages.
     *
     * <p>A ceiling on one sweep, not a target, and sized from what a sweep costs: a long
     * position list is a couple of thousand tokens of answer, so twenty of them is a few
     * minutes on the CUDA build. Reaching it is not an error — the next sweep continues
     * where this one stopped, because the queue is "everything with no parse row" and that
     * shrinks as it goes.
     */
    public static final int DEFAULT_BATCH_SIZE = 20;

    /**
     * Thirty days.
     *
     * <p>The window that stops this reading a mailbox's whole history the first time it is
     * switched on — 345 unparsed messages on a modest local copy, most of them years old.
     * A three-year-old position list is not information: the ship sailed and the cargo fixed,
     * so parsing it spends GPU to put rows on Open Fleet that are wrong by construction, and
     * they are the kind of wrong that looks right until somebody offers the ship.
     *
     * <p>Matched to {@code IMAP_INITIAL_DAYS}, which is how much mail a fresh sync fetches in
     * the first place — the two windows answering to the same idea of how far back is worth
     * looking. 0 turns it off, for the deliberate case of working through an old folder.
     */
    public static final int DEFAULT_MAX_AGE_DAYS = 30;

    /** Ten years. Past that it is not a window, it is a typo. */
    private static final int MAX_AGE_DAYS_LIMIT = 3650;

    /** A day between sweeps is already absurd; beyond that it is a typo. */
    private static final int MAX_INTERVAL_MINUTES = 1440;

    /** One sweep is one transaction per message and minutes of GPU. A hundred is plenty. */
    private static final int MAX_BATCH_SIZE = 100;

    /**
     * "No limit", as a date rather than a null.
     *
     * <p>Older than any mailbox and older than the epoch this application deals in, so it
     * excludes nothing. A null would read more honestly and cannot be used: Postgres refuses
     * a statement containing {@code (? is null or ...)} because it cannot infer the
     * parameter's type, and the failure is a 500 rather than a wrong answer.
     */
    public static final java.time.LocalDateTime NO_LIMIT_SINCE =
            java.time.LocalDateTime.of(1900, 1, 1, 0, 0);

    private final AppSettingRepository repository;
    private final ParserProperties props;

    /** What the Settings tab shows and sends back. */
    public record Values(int sweepIntervalMinutes, int sweepBatchSize, int sweepMaxAgeDays) {

        /**
         * The oldest mail a sweep may pick up. Never null — see {@link #NO_LIMIT_SINCE}.
         *
         * <p>On the value rather than in the sweep so the count in the header and the queue
         * the sweep actually reads cannot be computed from two different cutoffs — which
         * would show "0 waiting" above a run that then read twenty.
         */
        public java.time.LocalDateTime receivedSince() {
            return sweepMaxAgeDays <= 0 ? NO_LIMIT_SINCE
                    : java.time.LocalDateTime.now().minusDays(sweepMaxAgeDays);
        }
    }

    @Transactional(readOnly = true)
    public Values values() {
        Map<String, String> stored = repository
                .findByKeyIn(List.of(SWEEP_INTERVAL_MINUTES, SWEEP_BATCH_SIZE, SWEEP_MAX_AGE_DAYS))
                .stream()
                .collect(Collectors.toMap(AppSetting::getKey, AppSetting::getValue));
        return new Values(
                readInt(stored, SWEEP_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES),
                readInt(stored, SWEEP_BATCH_SIZE, DEFAULT_BATCH_SIZE),
                readInt(stored, SWEEP_MAX_AGE_DAYS, DEFAULT_MAX_AGE_DAYS));
    }

    public static Values defaults() {
        return new Values(DEFAULT_INTERVAL_MINUTES, DEFAULT_BATCH_SIZE, DEFAULT_MAX_AGE_DAYS);
    }

    /**
     * Read on every tick, which is once a minute — cheap enough to be worth not caching.
     *
     * <p>A cache here would need invalidating from the settings write, and the failure it
     * would cause is the worst kind: a user turns the sweep off, watches it run again, and
     * has no way to tell whether the setting did not save or the app did not notice.
     */
    @Transactional(readOnly = true)
    public int sweepIntervalMinutes() {
        return values().sweepIntervalMinutes();
    }

    @Transactional(readOnly = true)
    public int sweepBatchSize() {
        return values().sweepBatchSize();
    }

    /**
     * Where the model is, and whether that is this instance's own answer or the configured one.
     *
     * <p>Read on every call rather than cached, for {@link #sweepIntervalMinutes()}'s reason: a
     * cache would need invalidating from the write, and the failure it causes is the worst kind —
     * somebody repoints the address, watches the old server answer, and cannot tell whether the
     * setting did not save or the app did not notice.
     */
    @Transactional(readOnly = true)
    public ModelEndpoint endpoint() {
        Map<String, String> stored = repository.findByKeyIn(MODEL_KEYS).stream()
                .collect(Collectors.toMap(AppSetting::getKey, AppSetting::getValue));
        String configuredUrl = blankToEmpty(props.getUrl());
        String configuredModel = blankToEmpty(props.getModel());
        return new ModelEndpoint(
                readText(stored, MODEL_URL, configuredUrl),
                readText(stored, MODEL_NAME, configuredModel),
                configuredUrl,
                configuredModel);
    }

    /**
     * The address and the model name. Null leaves either alone; blank restores the configured one.
     *
     * <p>Its own method rather than three more arguments on {@link #update}: the pacing knobs and
     * the address are edited on separate cards and saved separately, and a single method would
     * have each form sending nulls for the other's fields.
     */
    @Transactional
    public ModelEndpoint updateEndpoint(String modelUrl, String modelName) {
        if (modelUrl != null) {
            putOrClear(MODEL_URL,
                    modelUrl.isBlank() ? "" : ModelEndpoint.requireCompletionsUrl(modelUrl),
                    blankToEmpty(props.getUrl()));
        }
        if (modelName != null) {
            putOrClear(MODEL_NAME, modelName.strip(), blankToEmpty(props.getModel()));
        }
        ModelEndpoint endpoint = endpoint();
        log.info("Parser model endpoint: {}{} ({})", endpoint.url(),
                endpoint.model().isEmpty() ? "" : " model " + endpoint.model(),
                endpoint.urlCustomised() || endpoint.modelCustomised()
                        ? "set here" : "from the environment");
        return endpoint;
    }

    @Transactional
    public ModelEndpoint resetEndpoint() {
        repository.deleteByKeyIn(MODEL_KEYS);
        return endpoint();
    }

    @Transactional
    public Values update(Integer intervalMinutes, Integer batchSize, Integer maxAgeDays) {
        if (intervalMinutes != null) {
            if (intervalMinutes < 0 || intervalMinutes > MAX_INTERVAL_MINUTES) {
                throw new IllegalArgumentException(
                        "The interval must be between 0 (off) and " + MAX_INTERVAL_MINUTES
                                + " minutes.");
            }
            put(SWEEP_INTERVAL_MINUTES, String.valueOf(intervalMinutes));
        }
        if (batchSize != null) {
            if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException(
                        "A sweep must read between 1 and " + MAX_BATCH_SIZE + " messages.");
            }
            put(SWEEP_BATCH_SIZE, String.valueOf(batchSize));
        }
        if (maxAgeDays != null) {
            if (maxAgeDays < 0 || maxAgeDays > MAX_AGE_DAYS_LIMIT) {
                throw new IllegalArgumentException(
                        "The lookback must be between 0 (no limit) and " + MAX_AGE_DAYS_LIMIT
                                + " days.");
            }
            put(SWEEP_MAX_AGE_DAYS, String.valueOf(maxAgeDays));
        }
        Values values = values();
        log.info("Parser settings updated: every {} minutes, {} messages a sweep, "
                        + "mail from the last {}",
                values.sweepIntervalMinutes() == 0 ? "— (off)" : values.sweepIntervalMinutes(),
                values.sweepBatchSize(),
                values.sweepMaxAgeDays() == 0 ? "— (no limit)" : values.sweepMaxAgeDays() + " days");
        return values;
    }

    @Transactional
    public Values reset() {
        repository.deleteByKeyIn(
                List.of(SWEEP_INTERVAL_MINUTES, SWEEP_BATCH_SIZE, SWEEP_MAX_AGE_DAYS));
        return values();
    }

    /**
     * A value equal to the configured one is not stored.
     *
     * <p>The same rule an unedited feed prompt follows, and what keeps "customised" meaningful:
     * typing the address that is already in force leaves the environment owning it, so a later
     * change to {@code PARSER_URL} still reaches this instance.
     */
    private void putOrClear(String key, String value, String configured) {
        if (value.isEmpty() || value.equals(configured)) {
            repository.deleteByKeyIn(List.of(key));
        } else {
            put(key, value);
        }
    }

    private static String readText(Map<String, String> stored, String key, String fallback) {
        String raw = stored.get(key);
        return raw == null || raw.isBlank() ? fallback : raw.strip();
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.strip();
    }

    private void put(String key, String value) {
        AppSetting s = repository.findById(key).orElseGet(() -> {
            AppSetting fresh = new AppSetting();
            fresh.setKey(key);
            return fresh;
        });
        s.setValue(value);
        repository.save(s);
    }

    /** A malformed row must not stop the sweep; fall back and say so, as the mail side does. */
    private static int readInt(Map<String, String> stored, String key, int fallback) {
        String raw = stored.get(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Setting {} holds an unreadable value '{}', using the default", key, raw);
            return fallback;
        }
    }
}
