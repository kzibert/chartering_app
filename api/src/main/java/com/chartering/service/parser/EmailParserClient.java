package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.service.AnalysisAnnotationTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The one thing in this application that talks to the model.
 *
 * <p>An OpenAI-shaped chat completion against llama-server, and the request is put together
 * to match the measurement rather than to look tidy — see {@code chartering-ml}'s
 * {@code src/serve_check.py}, which scored the served model with exactly this payload. Four
 * parts of it are load-bearing:
 *
 * <ul>
 *   <li><b>The system prompt is {@link AnalysisAnnotationTemplates#SYSTEM_PROMPT}</b>, the
 *       same string every training example was written against. Not a copy of it — the
 *       constant itself. A prompt that drifts from the one the model was finetuned on is the
 *       cheapest way to lose accuracy while every test still passes.
 *   <li><b>The user turn is Date, Subject, blank line, body</b>, exactly as
 *       {@code AnalysisExportService} builds it. The date is not decoration: the model is
 *       told to take the year for "OPEN 07/10 SEPTEMBER" from the email's own date, and
 *       without it every laycan comes back yearless and every timing check goes UNKNOWN.
 *   <li><b>The JSON schema goes on the request, not on the server.</b> It is what makes a
 *       markdown fence, a stray key and a flag of 0 structurally impossible, and it is worth
 *       0.16 of vessel F1 on its own — unconstrained, the model degenerates on long position
 *       lists and pads out fifty empty vessel objects where the gold has eight. Sent per
 *       request because that is how it was measured, and because a server pinned to one
 *       grammar could not serve anything else.
 *   <li><b>{@code temperature 0}</b>. This is extraction, not writing.
 * </ul>
 *
 * <p>It deliberately holds no Spring HTTP machinery. {@code java.net.http} is in the JDK,
 * the call is one POST with two timeouts, and the interesting failure — the workstation
 * being asleep — has to arrive as a plain message a user can act on rather than as a wrapped
 * client exception five frames deep.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailParserClient {

    private static final String SCHEMA_RESOURCE = "parser/extraction-schema.json";

    private final ParserProperties props;
    private final ObjectMapper json;

    /**
     * The grammar, read once at startup.
     *
     * <p>A copy of {@code chartering-ml}'s {@code serve/schema.json}, which that project
     * generates from the same annotation templates this one holds. Copied rather than
     * referenced because the two repositories deploy separately and a container cannot read
     * a file from a sibling checkout — and pinned in the build for the same reason the
     * prompt is a constant: the schema the request carries has to be the schema the model
     * was measured under. If the templates here grow a field, regenerate it there
     * ({@code make schema}) and copy it back.
     */
    private JsonNode schema;

    /** Reachability, so the tab can say "the model server is not running" before a sweep. */
    private volatile String lastError;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5_000))
            .build();

    @PostConstruct
    void loadSchema() {
        try (InputStream in = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            schema = json.readTree(in);
        } catch (IOException e) {
            // Not fatal to startup: with the parser disabled — which is the default and the
            // hosted case — nothing will ever ask for it, and refusing to boot over a
            // resource an unused feature needs would take the whole app down for it.
            log.warn("Could not read {}; the parser will run unconstrained if it runs at all",
                    SCHEMA_RESOURCE, e);
        }
    }

    /**
     * What one email cost and what it said.
     *
     * @param content   the model's answer, verbatim, before any parsing
     * @param model     which model answered, as the endpoint reported it
     * @param durationMs wall clock for the request, which is what the Intake tab reports
     */
    public record Completion(String content, String model, int durationMs, int promptChars) {
    }

    /** Failure that is worth another go later, and worth a message the user can act on. */
    public static class ParserUnavailableException extends RuntimeException {
        public ParserUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public ParserUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Read one email.
     *
     * @param sentAt  the sender's own clock where there is one — the date the "07/10
     *                SEPTEMBER" in the body was written against. A message that sat in a
     *                queue overnight would otherwise be dated a day after the laycan it
     *                announces, which is the same reason the corpus export prefers it.
     */
    public Completion complete(String subject, LocalDateTime sentAt, String bodyText) {
        String user = userTurn(subject, sentAt, bodyText);
        String payload = requestBody(user);

        HttpRequest request = HttpRequest.newBuilder(URI.create(props.getUrl()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        long started = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParserUnavailableException("The parse was interrupted.", e);
        } catch (IOException e) {
            // The common case by a wide margin, and the message has to name the cure: the
            // model server is a container on somebody's desk, and the desk gets turned off.
            lastError = e.getMessage();
            throw new ParserUnavailableException(
                    "Could not reach the model at " + props.getUrl() + " — " + e.getMessage()
                            + ". Start it with: docker compose -f serve/docker-compose.llamacpp.yml"
                            + " up -d (in the chartering-ml project).", e);
        }
        int durationMs = (int) (System.currentTimeMillis() - started);

        if (response.statusCode() / 100 != 2) {
            lastError = "HTTP " + response.statusCode();
            throw new ParserUnavailableException("The model answered HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }

        try {
            JsonNode body = json.readTree(response.body());
            JsonNode message = body.path("choices").path(0).path("message").path("content");
            if (message.isMissingNode() || !message.isTextual()) {
                throw new ParserUnavailableException(
                        "The model's reply carried no message content: " + abbreviate(response.body()));
            }
            lastError = null;
            return new Completion(message.asText(), body.path("model").asText(null),
                    durationMs, user.length());
        } catch (IOException e) {
            lastError = e.getMessage();
            throw new ParserUnavailableException(
                    "The model's reply was not JSON: " + abbreviate(response.body()), e);
        }
    }

    /**
     * Is the server there?
     *
     * <p>Its own request rather than an inference from the last parse, because the tab asks
     * this before running anything and "we have not tried since Friday" is not an answer.
     * The endpoint's sibling {@code /health} is what llama-server exposes and what its own
     * container healthchecks; deriving it from the completions url keeps one setting rather
     * than two that can point at different servers.
     */
    public boolean isReachable() {
        String base = props.getUrl();
        int api = base.indexOf("/v1/");
        String health = (api > 0 ? base.substring(0, api) : base) + "/health";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(health))
                    .timeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() / 100 == 2;
            lastError = ok ? null : "HTTP " + response.statusCode() + " from " + health;
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }
    }

    /** The last thing that went wrong, for the tab to print under a failed reachability check. */
    public String lastError() {
        return lastError;
    }

    // --------------------------------------------------------------- internals

    private String requestBody(String user) {
        ObjectNode root = json.createObjectNode();
        // llama-server serves one model and ignores this; Ollama refuses the request without
        // it, since it can hold many. Sent only when configured, so the normal case carries
        // nothing it does not need.
        if (props.getModel() != null && !props.getModel().isBlank()) {
            root.put("model", props.getModel().trim());
        }
        var messages = root.putArray("messages");
        messages.addObject().put("role", "system")
                .put("content", AnalysisAnnotationTemplates.SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", user);
        root.put("temperature", 0);
        // From configuration, not a literal: a request-level max_tokens overrides the
        // server's own num_predict, so a stale number here silently truncates long answers
        // and the model gets blamed for it.
        root.put("max_tokens", props.getMaxTokens());
        root.put("stream", false);
        if (schema != null) {
            ObjectNode format = root.putObject("response_format");
            format.put("type", "json_schema");
            ObjectNode named = format.putObject("json_schema");
            named.put("name", "chartering_extraction");
            named.set("schema", schema);
        }
        return root.toString();
    }

    /**
     * The email as the model was trained to read it: Date, Subject, a blank line, the body.
     *
     * <p>Kept identical to {@code AnalysisExportService#userTurn} on purpose. The corpus was
     * exported in this layout and the model learned the layout along with the content, so
     * the two have to move together — a difference here would be invisible in every test and
     * would show up only as the model reading slightly worse than it measures.
     */
    private String userTurn(String subject, LocalDateTime sentAt, String bodyText) {
        StringBuilder sb = new StringBuilder();
        if (sentAt != null) {
            // The day, not the timestamp. Nothing in a circular resolves to an hour.
            LocalDate day = sentAt.toLocalDate();
            sb.append("Date: ").append(day).append('\n');
        }
        if (subject != null && !subject.isBlank()) {
            sb.append("Subject: ").append(subject.strip()).append('\n');
        }
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(trimBody(bodyText));
        return sb.toString();
    }

    private String trimBody(String body) {
        String trimmed = body == null ? "" : body.strip();
        return trimmed.length() <= props.getMaxBodyChars()
                ? trimmed
                : trimmed.substring(0, props.getMaxBodyChars());
    }

    /** Enough of a bad reply to recognise it, without putting a megabyte in a text column. */
    private static String abbreviate(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
