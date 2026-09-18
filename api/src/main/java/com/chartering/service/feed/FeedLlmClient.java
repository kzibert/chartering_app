package com.chartering.service.feed;

import com.chartering.config.ParserProperties;
import com.chartering.service.ModelEndpoint;
import com.chartering.service.ParserSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
 * <p>It also asks llama-server two things the parser never needs: how many tokens a text is
 * ({@code /tokenize}, so budgets are counted rather than guessed) and how big its window is
 * ({@code /props}, for the Settings card's Detect button).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeedLlmClient implements TokenCounter {

    /** A conservative guess where the server will not count: English runs about four characters a token, Russian nearer two. */
    private static final double CHARS_PER_TOKEN_FALLBACK = 2.5;

    /** Timeouts only; the address and the model name come from the settings below. */
    private final ParserProperties props;
    private final FeedSettings settings;
    private final ParserSettings parser;
    private final ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5_000))
            .build();

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
        ObjectNode root = json.createObjectNode();
        if (!endpoint.model().isBlank()) {
            root.put("model", endpoint.model());
        }
        var messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        // 0, as the server is started with: a summary of rates should read the same figures the
        // same way twice, and nothing here benefits from variety.
        root.put("temperature", 0);
        root.put("max_tokens", maxTokens);
        root.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8))
                .build();
        long started = System.currentTimeMillis();
        HttpResponse<String> response = send(request, endpoint.url());
        int durationMs = (int) (System.currentTimeMillis() - started);
        if (response.statusCode() / 100 != 2) {
            throw new ModelUnavailableException("The model answered HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        try {
            JsonNode body = json.readTree(response.body());
            JsonNode content = body.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new ModelUnavailableException("The model's reply carried no text: " + abbreviate(response.body()));
            }
            JsonNode usage = body.path("usage");
            return new Completion(content.asText().strip(), body.path("model").asText(null),
                    usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0),
                    durationMs);
        } catch (IOException e) {
            throw new ModelUnavailableException("The model's reply was not JSON: " + abbreviate(response.body()), e);
        }
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

    private HttpResponse<String> send(HttpRequest request, String url) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("The summary was interrupted.", e);
        } catch (IOException e) {
            throw new ModelUnavailableException("Could not reach the model at " + url + " — "
                    + e.getMessage() + ". Start it in chartering-ml.", e);
        }
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
