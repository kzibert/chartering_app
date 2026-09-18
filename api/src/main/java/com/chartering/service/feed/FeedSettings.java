package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.model.AppSetting;
import com.chartering.repository.AppSettingRepository;
import com.chartering.service.ModelEndpoint;
import com.chartering.service.ParserSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Feed's runtime settings: the model's window, how much of it an answer may take, how far
 * back to read, how often to fetch, and the two prompts.
 *
 * <p>In {@code app_settings} for the parser settings' reason — these are turned while reading
 * the output ("that summary was cut short", "read a fortnight, not a week") and an environment
 * variable is a redeploy. Only overrides are stored; an absent row is the default, so reset is a
 * delete. The prompts follow the same rule: an unedited prompt has no row, which is what lets a
 * better default reach everyone who never changed theirs.
 *
 * <p><b>The model's address is here too, and it is the setting this side most needs.</b> An 8GB
 * card cannot hold the extraction finetune and a general instruct model at once, so the two
 * servers are swapped — and which port is up at any moment is exactly the kind of thing that
 * must not be a redeploy. See {@link #endpoint()} for the fallback chain, and
 * {@link ModelEndpoint} for why a value equal to the configured one is deleted rather than kept.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedSettings {

    public static final String CONTEXT_WINDOW_TOKENS = "feed.contextWindowTokens";
    public static final String SUMMARY_MAX_TOKENS = "feed.summaryMaxTokens";
    public static final String NOTES_MAX_TOKENS = "feed.notesMaxTokens";
    public static final String LOOKBACK_DAYS = "feed.lookbackDays";
    public static final String MAX_CALLS_PER_TOPIC = "feed.maxCallsPerTopic";
    public static final String FETCH_INTERVAL_MINUTES = "feed.fetchIntervalMinutes";
    public static final String SYSTEM_PROMPT = "feed.systemPrompt";
    public static final String NOTES_PROMPT = "feed.notesPrompt";

    /** The chat-completions endpoint summaries go to. Absent means {@code FEED_LLM_URL}. */
    public static final String MODEL_URL = "feed.modelUrl";

    /** The model name to send with them. Absent means {@code FEED_LLM_MODEL}. */
    public static final String MODEL_NAME = "feed.modelName";

    private static final List<String> MODEL_KEYS = List.of(MODEL_URL, MODEL_NAME);

    private static final List<String> NUMBER_KEYS = List.of(CONTEXT_WINDOW_TOKENS, SUMMARY_MAX_TOKENS,
            NOTES_MAX_TOKENS, LOOKBACK_DAYS, MAX_CALLS_PER_TOPIC, FETCH_INTERVAL_MINUTES);
    private static final List<String> PROMPT_KEYS = List.of(SYSTEM_PROMPT, NOTES_PROMPT);

    /**
     * 8,192 tokens: what the model is served with today ({@code --ctx-size 8192} in
     * chartering-ml's llama.cpp compose file, and what its {@code /props} reports). Not read from
     * the server at startup, because the hosted instance has no server to ask; the Settings card's
     * "Detect from model" asks it on a machine that has one.
     */
    public static final int DEFAULT_CONTEXT_WINDOW = 8_192;

    /** A summary is an overview and eight bullets; a thousand tokens is room for that with slack. */
    public static final int DEFAULT_SUMMARY_MAX_TOKENS = 1_024;

    /** Notes on one batch are a list of facts, and every token of them is a token the next stage must fit. */
    public static final int DEFAULT_NOTES_MAX_TOKENS = 384;

    /** A week: freight talk older than that is history rather than market. */
    public static final int DEFAULT_LOOKBACK_DAYS = 7;

    /**
     * Twelve model calls per topic: at 81 tokens a second, a couple of minutes of GPU. Past it the
     * lowest-ranked items are dropped, and the summary says how many.
     */
    public static final int DEFAULT_MAX_CALLS_PER_TOPIC = 12;

    /** An hour. The boards and channels read here change a few times a day. */
    public static final int DEFAULT_FETCH_INTERVAL_MINUTES = 60;

    /** Nothing smaller leaves room for a prompt, one item and an answer. */
    static final int MIN_CONTEXT_WINDOW = 2_048;
    private static final int MAX_CONTEXT_WINDOW = 1_048_576;
    private static final int MAX_PROMPT_CHARS = 20_000;

    /** What must be left for the material once the prompt and the answer are reserved. */
    private static final int MIN_MATERIAL_TOKENS = 512;

    private final AppSettingRepository repository;
    private final FeedProperties props;

    /**
     * For the last link of the fallback chain, not for the feed's own setting.
     *
     * <p>Blank here has always meant "the parser's server", and it has to mean the parser's
     * <i>effective</i> one now that that is itself a setting — otherwise pointing the parser at a
     * new box would leave the feed quietly talking to the old one, which is the failure a shared
     * default exists to prevent.
     */
    private final ParserSettings parser;

    public record Values(int contextWindowTokens, int summaryMaxTokens, int notesMaxTokens,
                         int lookbackDays, int maxCallsPerTopic, int fetchIntervalMinutes,
                         String systemPrompt, String notesPrompt,
                         boolean systemPromptCustomised, boolean notesPromptCustomised) {
    }

    /** A partial update: null leaves a value as it is. A blank prompt restores the default. */
    public record Update(Integer contextWindowTokens, Integer summaryMaxTokens, Integer notesMaxTokens,
                         Integer lookbackDays, Integer maxCallsPerTopic, Integer fetchIntervalMinutes,
                         String systemPrompt, String notesPrompt) {
    }

    @Transactional(readOnly = true)
    public Values values() {
        Map<String, String> stored = repository
                .findByKeyIn(concat(NUMBER_KEYS, PROMPT_KEYS)).stream()
                .collect(Collectors.toMap(AppSetting::getKey, AppSetting::getValue));
        String system = stored.get(SYSTEM_PROMPT);
        String notes = stored.get(NOTES_PROMPT);
        return new Values(
                readInt(stored, CONTEXT_WINDOW_TOKENS, DEFAULT_CONTEXT_WINDOW),
                readInt(stored, SUMMARY_MAX_TOKENS, DEFAULT_SUMMARY_MAX_TOKENS),
                readInt(stored, NOTES_MAX_TOKENS, DEFAULT_NOTES_MAX_TOKENS),
                readInt(stored, LOOKBACK_DAYS, DEFAULT_LOOKBACK_DAYS),
                readInt(stored, MAX_CALLS_PER_TOPIC, DEFAULT_MAX_CALLS_PER_TOPIC),
                readInt(stored, FETCH_INTERVAL_MINUTES, DEFAULT_FETCH_INTERVAL_MINUTES),
                isBlank(system) ? FeedPrompts.DEFAULT_SYSTEM : system,
                isBlank(notes) ? FeedPrompts.DEFAULT_NOTES : notes,
                !isBlank(system), !isBlank(notes));
    }

    public static Values defaults() {
        return new Values(DEFAULT_CONTEXT_WINDOW, DEFAULT_SUMMARY_MAX_TOKENS, DEFAULT_NOTES_MAX_TOKENS,
                DEFAULT_LOOKBACK_DAYS, DEFAULT_MAX_CALLS_PER_TOPIC, DEFAULT_FETCH_INTERVAL_MINUTES,
                FeedPrompts.DEFAULT_SYSTEM, FeedPrompts.DEFAULT_NOTES, false, false);
    }

    /**
     * Where summaries go: this setting, else {@code FEED_LLM_URL}, else the parser's endpoint.
     *
     * <p>Three links rather than two because the third is the shape a single-server install has,
     * and it stays honest — but it is not the shape to want. The parser's model is an extraction
     * finetune, and run against real feed items it reported "no vessel openings" for a position
     * list full of them and wrote eight Danube–Med rates that no item contained. What is
     * reported as configured is the second link, because that is what "reset" restores; the
     * third is a fallback and would make the Reset button claim to restore something it does not
     * store.
     */
    @Transactional(readOnly = true)
    public ModelEndpoint endpoint() {
        Map<String, String> stored = repository.findByKeyIn(MODEL_KEYS).stream()
                .collect(Collectors.toMap(AppSetting::getKey, AppSetting::getValue));
        ModelEndpoint parserEndpoint = parser.endpoint();
        String configuredUrl = firstNonBlank(props.getLlmUrl(), parserEndpoint.url());
        String configuredModel = blankToEmpty(props.getLlmModel());
        String url = firstNonBlank(stored.get(MODEL_URL), configuredUrl);
        String model = firstNonBlank(stored.get(MODEL_NAME), configuredModel);
        // The parser's model name belongs to the parser's server; sent to another one it names
        // nothing. So it is inherited only where the address was inherited too.
        if (model.isEmpty() && url.equals(parserEndpoint.url())) {
            model = parserEndpoint.model();
        }
        return new ModelEndpoint(url, model, configuredUrl, configuredModel);
    }

    /** The address and the model name. Null leaves either alone; blank restores the configured one. */
    @Transactional
    public ModelEndpoint updateEndpoint(String modelUrl, String modelName) {
        if (modelUrl != null) {
            putOrClear(MODEL_URL,
                    modelUrl.isBlank() ? "" : ModelEndpoint.requireCompletionsUrl(modelUrl),
                    endpoint().configuredUrl());
        }
        if (modelName != null) {
            putOrClear(MODEL_NAME, modelName.strip(), blankToEmpty(props.getLlmModel()));
        }
        ModelEndpoint endpoint = endpoint();
        log.info("Feed model endpoint: {}{} ({})", endpoint.url(),
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

    @Transactional(readOnly = true)
    public int fetchIntervalMinutes() {
        return values().fetchIntervalMinutes();
    }

    @Transactional
    public Values update(Update u) {
        Values current = values();
        validate(u, current);
        if (u.contextWindowTokens() != null) put(CONTEXT_WINDOW_TOKENS, u.contextWindowTokens());
        if (u.summaryMaxTokens() != null) put(SUMMARY_MAX_TOKENS, u.summaryMaxTokens());
        if (u.notesMaxTokens() != null) put(NOTES_MAX_TOKENS, u.notesMaxTokens());
        if (u.lookbackDays() != null) put(LOOKBACK_DAYS, u.lookbackDays());
        if (u.maxCallsPerTopic() != null) put(MAX_CALLS_PER_TOPIC, u.maxCallsPerTopic());
        if (u.fetchIntervalMinutes() != null) put(FETCH_INTERVAL_MINUTES, u.fetchIntervalMinutes());
        putPrompt(SYSTEM_PROMPT, u.systemPrompt(), FeedPrompts.DEFAULT_SYSTEM);
        putPrompt(NOTES_PROMPT, u.notesPrompt(), FeedPrompts.DEFAULT_NOTES);
        Values values = values();
        log.info("Feed settings updated: window {} tokens, summary {} / notes {}, {} days, {} calls, every {} min",
                values.contextWindowTokens(), values.summaryMaxTokens(), values.notesMaxTokens(),
                values.lookbackDays(), values.maxCallsPerTopic(), values.fetchIntervalMinutes());
        return values;
    }

    /** The numbers back to their defaults. The prompts are left alone — they have their own reset. */
    @Transactional
    public Values resetNumbers() {
        repository.deleteByKeyIn(NUMBER_KEYS);
        return values();
    }

    @Transactional
    public Values resetPrompts() {
        repository.deleteByKeyIn(PROMPT_KEYS);
        return values();
    }

    /**
     * Ranges, and the one cross-field rule that matters: the window has to hold an answer and
     * still leave room for material. A window of 2,048 with a 2,048-token summary would save and
     * then fail every run, which is a setting that looks saved and is not.
     */
    static void validate(Update u, Values current) {
        int window = u.contextWindowTokens() != null ? u.contextWindowTokens() : current.contextWindowTokens();
        int summary = u.summaryMaxTokens() != null ? u.summaryMaxTokens() : current.summaryMaxTokens();
        int notes = u.notesMaxTokens() != null ? u.notesMaxTokens() : current.notesMaxTokens();
        range("The context window", window, MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW, "tokens");
        range("The summary's answer", summary, 128, 32_768, "tokens");
        range("A notes answer", notes, 64, 16_384, "tokens");
        if (summary + MIN_MATERIAL_TOKENS > window || notes + MIN_MATERIAL_TOKENS > window) {
            throw new IllegalArgumentException("The context window (" + window + " tokens) must leave at least "
                    + MIN_MATERIAL_TOKENS + " tokens for material after the answer is reserved — lower the "
                    + "answer sizes or raise the window.");
        }
        if (u.lookbackDays() != null) range("The lookback", u.lookbackDays(), 1, 365, "days");
        if (u.maxCallsPerTopic() != null) range("Model calls per topic", u.maxCallsPerTopic(), 1, 200, "");
        if (u.fetchIntervalMinutes() != null) {
            range("The fetch interval", u.fetchIntervalMinutes(), 0, 1_440, "minutes (0 is off)");
        }
        for (String prompt : new String[]{u.systemPrompt(), u.notesPrompt()}) {
            if (prompt != null && prompt.length() > MAX_PROMPT_CHARS) {
                throw new IllegalArgumentException("A prompt may be at most " + MAX_PROMPT_CHARS + " characters.");
            }
        }
    }

    private static void range(String what, int value, int min, int max, String unit) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(what + " must be between " + min + " and " + max
                    + (unit.isEmpty() ? "" : " " + unit) + ".");
        }
    }

    private void putPrompt(String key, String value, String defaultText) {
        if (value == null) return;
        // Saving the default text verbatim is the same as not having edited it.
        if (value.isBlank() || value.strip().equals(defaultText.strip())) {
            repository.deleteByKeyIn(List.of(key));
        } else {
            put(key, value);
        }
    }

    /** A value equal to the configured one is not stored — {@code putPrompt}'s rule, for an address. */
    private void putOrClear(String key, String value, String configured) {
        if (value.isEmpty() || value.equals(configured)) {
            repository.deleteByKeyIn(List.of(key));
        } else {
            put(key, value);
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? blankToEmpty(fallback) : preferred.strip();
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.strip();
    }

    private void put(String key, Object value) {
        AppSetting s = repository.findById(key).orElseGet(() -> {
            AppSetting fresh = new AppSetting();
            fresh.setKey(key);
            return fresh;
        });
        s.setValue(String.valueOf(value));
        repository.save(s);
    }

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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
