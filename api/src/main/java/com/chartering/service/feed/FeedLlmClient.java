package com.chartering.service.feed;

import com.chartering.config.ParserProperties;
import com.chartering.service.ModelEndpoint;
import com.chartering.service.ParserSettings;
import com.chartering.service.SpringAiClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Feed's conversation with the local model.
 *
 * <p><b>A sibling of {@code EmailParserClient}, not a reuse of it.</b> That client's system
 * prompt, JSON schema and Date/Subject user turn are pinned to what the model was measured
 * under, and each is load-bearing for extraction. A summary wants none of them: its prompt is the
 * user's, its answer is prose, and sending the extraction schema would make prose structurally
 * impossible. The address is not shared either: {@link FeedSettings#endpoint()} answers it, and
 * only falls back to the parser's where nothing else says otherwise — the single-server shape,
 * which works and is not the one to want.
 *
 * <p><b>The chat call goes through Spring AI; the parser's does not, and that split is deliberate.</b>
 * The Feed's request is free: a summary is prose, so a framework that shapes the request, adds
 * fields or retries it costs nothing that matters here, and the library gives tested error types
 * and token usage for free. The parser's request is the opposite case. Its system prompt, its
 * user-turn layout and its {@code response_format} are the exact bytes the finetune was measured
 * under, and a library rewriting any of them is a silent change to extraction accuracy with every
 * test still green. So the parser keeps its own request, and this client trades that control for
 * the library's, where there is nothing to lose.
 *
 * <p>Only the chat call is Spring AI. It also asks llama-server two things the chat model has no
 * part in, and those stay plain HTTP: how many tokens a text is ({@code /tokenize}, so budgets are
 * counted rather than guessed), how big its window is ({@code /props}, for the Settings card's
 * Detect button), and whether it is up ({@code /health}). Spring AI has no model for any of them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeedLlmClient implements TokenCounter {

    /** A conservative guess where the server will not count: English runs about four characters a token, Russian nearer two. */
    private static final double CHARS_PER_TOKEN_FALLBACK = 2.5;

    /** The path llama-server and OpenAI-shaped servers answer chat on, when the address names none. */
    private static final String CHAT_PATH = "/v1/chat/completions";

    /**
     * The name sent when the settings name none.
     *
     * <p>llama-server serves one model and ignores the field. An Ollama refuses a request without
     * one, and its error then names this placeholder rather than a missing field. It must never be
     * left blank: Spring AI fills in its own default name, which is an OpenAI model that a local
     * server has never heard of, and that name must not go out to a local server.
     */
    private static final String UNNAMED_MODEL = "local";

    /** The connect timeout the chat call has always had. Short, so a server that is off fails at once. */
    private static final Duration CHAT_CONNECT_TIMEOUT = Duration.ofMillis(5_000);

    /** Timeouts only; the address and the model name come from the settings below. */
    private final ParserProperties props;
    private final FeedSettings settings;
    private final ParserSettings parser;
    private final ObjectMapper json;

    /** For the sibling endpoints only — {@code /tokenize}, {@code /props}, {@code /health}. The chat call is {@link #models}. */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5_000))
            .build();

    /**
     * One chat model per address and model name, built on first use.
     *
     * <p>Keyed by the two resolved values, so repointing the address in Settings is picked up on the
     * next call without a restart, and a model that is not in use any more is simply never asked.
     */
    private final Map<ChatKey, OpenAiChatModel> models = new ConcurrentHashMap<>();

    private record ChatKey(String url, String model) {
    }

    public record Completion(String content, String model, int promptTokens, int completionTokens,
                             int durationMs) {
    }

    /** The server did not answer usefully. Stops a run: the next topic would fail the same way. */
    public static class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public ModelUnavailableException(String message) {
            super(message);
        }
    }

    public Completion chat(String system, String user, int maxTokens) {
        ModelEndpoint endpoint = settings.endpoint();
        String model = endpoint.model().isBlank() ? UNNAMED_MODEL : endpoint.model();
        OpenAiChatModel chat = models.computeIfAbsent(new ChatKey(endpoint.url(), model), this::build);

        // 0, as the server is started with: a summary of rates should read the same figures the
        // same way twice, and nothing here benefits from variety. The model is named on every call
        // as well as on the model's defaults, so the name in the request is always the one resolved here.
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)),
                OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .maxTokens(maxTokens)
                        .build());
        long started = System.currentTimeMillis();
        ChatResponse response;
        try {
            response = chat.call(prompt);
        } catch (RuntimeException e) {
            throw unavailable(e, endpoint.url());
        }
        int durationMs = (int) (System.currentTimeMillis() - started);

        Generation generation = response.getResult();
        String content = generation == null || generation.getOutput() == null
                ? null : generation.getOutput().getText();
        if (content == null) {
            throw new ModelUnavailableException("The model's reply carried no text.");
        }
        ChatResponseMetadata meta = response.getMetadata();
        Usage usage = meta == null ? null : meta.getUsage();
        return new Completion(content.strip(), meta == null ? null : meta.getModel(),
                tokens(usage == null ? null : usage.getPromptTokens()),
                tokens(usage == null ? null : usage.getCompletionTokens()),
                durationMs);
    }

    /**
     * Tokens in a text, counted by the server's own tokenizer.
     *
     * <p>Falls back to a deliberately pessimistic estimate where the server cannot count — an
     * Ollama, say. Over-estimating costs an extra batch; under-estimating costs a request the
     * server refuses for exceeding its window, which is the failure budgeting exists to prevent.
     */
    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            ObjectNode body = json.createObjectNode().put("content", text);
            HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/tokenize"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                JsonNode tokens = json.readTree(response.body()).path("tokens");
                if (tokens.isArray()) return tokens.size();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Token count unavailable, estimating: {}", e.toString());
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN_FALLBACK);
    }

    /** The window the server is running with, or null when it cannot say. */
    public Integer contextWindow() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/props"))
                    .timeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return null;
            JsonNode root = json.readTree(response.body());
            // Per slot, which is what one request may use; the top-level figure where it is absent.
            JsonNode perSlot = root.path("default_generation_settings").path("n_ctx");
            if (perSlot.isInt()) return perSlot.asInt();
            JsonNode total = root.path("n_ctx");
            return total.isInt() ? total.asInt() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/health"))
                    .timeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public String modelUrl() {
        return settings.endpoint().url();
    }

    /**
     * True when summaries go to the parser's own server — the case where a summary run must not
     * overlap a parser sweep, because the two would share one KV cache.
     *
     * <p>Against the parser's <i>effective</i> address, which is the whole point of asking: the
     * two features are pointed by hand now, and the 409 has to fire on where they are actually
     * aimed rather than on where two environment variables were aimed at boot.
     */
    public boolean sharesServerWithParser() {
        return settings.endpoint().url().equals(parser.endpoint().url());
    }

    /**
     * The chat model for one address and model name.
     *
     * <p>The default options carry the model and the temperature as well as the per-call options do.
     * Without them Spring AI's own default name would apply to any call that did not set one, which
     * is exactly the name {@link #UNNAMED_MODEL} exists to keep off a local server.
     *
     * <p>No retries, through {@link SpringAiClients#noRetry()}. A server that is mid-swap (see
     * CLAUDE.md) should fail the call at once, and the caller decides what to do with the failure.
     */
    private OpenAiChatModel build(ChatKey key) {
        URI uri;
        try {
            uri = URI.create(key.url());
        } catch (IllegalArgumentException e) {
            throw new ModelUnavailableException("The model's address is not a URL: " + key.url(), e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new ModelUnavailableException("The model's address is not an absolute URL: " + key.url());
        }

        SpringAiClients.Endpoint endpoint = SpringAiClients.split(uri, CHAT_PATH);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(SpringAiClients.NO_KEY)
                .completionsPath(endpoint.path())
                .restClientBuilder(SpringAiClients.restClient(CHAT_CONNECT_TIMEOUT,
                        Duration.ofMillis(props.getReadTimeoutMs())))
                // Spring AI's own handler throws types that do not carry the status code. Spring's
                // keeps the code and the body, which is what the failure message needs to say.
                .responseErrorHandler(new DefaultResponseErrorHandler())
                .build();

        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .model(key.model())
                .temperature(0.0)
                .build();
        log.info("Feed model client for {} (model {})", key.url(), key.model());
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(defaults)
                .retryTemplate(SpringAiClients.noRetry())
                .build();
    }

    /**
     * Maps whatever Spring AI and RestClient threw onto the two failures the callers know.
     *
     * <p>Shown to the person in the Feed run's message, so each one names what to do. The HTTP
     * status comes first, because a server that answered is not the same as one that did not, and
     * a connection failure comes before the rest, because it is what a stopped server looks like.
     */
    private ModelUnavailableException unavailable(RuntimeException e, String url) {
        if (Thread.currentThread().isInterrupted()) {
            return new ModelUnavailableException("The summary was interrupted.", e);
        }
        RestClientResponseException answered = findCause(e, RestClientResponseException.class);
        if (answered != null) {
            return new ModelUnavailableException("The model answered HTTP " + answered.getStatusCode().value()
                    + ": " + abbreviate(answered.getResponseBodyAsString()), e);
        }
        // A refused connection or a timed-out read. Spring AI wraps a read timeout in a plain
        // RestClientException rather than ResourceAccessException, so the socket types are checked too.
        if (findCause(e, ResourceAccessException.class) != null
                || findCause(e, InterruptedIOException.class) != null
                || findCause(e, SocketException.class) != null) {
            return new ModelUnavailableException("Could not reach the model at " + url + " — "
                    + rootMessage(e) + ". Start it in chartering-ml.", e);
        }
        return new ModelUnavailableException("The model's reply could not be read: " + rootMessage(e), e);
    }

    private static int tokens(Integer n) {
        return n == null ? 0 : n;
    }

    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return type.cast(t);
        }
        return null;
    }

    /** The innermost message, which is the connection error itself rather than RestClient's wrapper around it. */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    /**
     * The server root, for the sibling endpoints.
     *
     * <p>A settings read per call, including per {@code /tokenize} — which is a database read on
     * a table of a dozen rows in front of an HTTP round trip, so it is noise beside what it
     * decorates, and it is what lets a server swapped mid-session be noticed at once.
     */
    private String base() {
        return settings.endpoint().base();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
