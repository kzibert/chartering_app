package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.service.AnalysisAnnotationTemplates;
import com.chartering.service.ModelEndpoint;
import com.chartering.service.ParserSettings;
import com.chartering.service.TrainingTurns;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

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
 * <p><b>Where the model is comes from {@link ParserSettings}, not from
 * {@link ParserProperties}.</b> {@code PARSER_URL} is behind it as the default, but the address
 * is read per request so that repointing it on the Settings tab takes effect on the next
 * message rather than on the next restart — which is the point of having it there, since the
 * extraction server and the Feed's general model are swapped on one card.
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

    /**
     * The longest a cloud rate limit is waited out inside one email, in seconds.
     *
     * <p>Beyond this the wait is the provider's "come back later", and the sweep stops instead of holding
     * a transaction open for minutes. A minute is what a per-minute token allowance refills in.
     */
    private static final int MAX_RATE_LIMIT_WAIT_SECONDS = 60;

    /** How many times one cloud call is retried after a short rate limit before the sweep stops. */
    private static final int MAX_RATE_LIMIT_RETRIES = 5;

    private final ParserProperties props;
    private final ParserSettings settings;
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
     * @param content     the model's answer, verbatim, before any parsing
     * @param model       which model answered, as the endpoint reported it
     * @param durationMs  wall clock for the request, which is what the Intake tab reports
     * @param promptChars the characters sent in the user turns, examples included, so the Log
     *                    shows what a few-shot parse actually cost
     * @param exampleIds  the corpus samples shown to the model, in the order sent; empty when
     *                    the parse was zero-shot. Kept so a wrong answer can be traced to the
     *                    examples that were in front of it.
     */
    public record Completion(String content, String model, int durationMs, int promptChars,
                             List<Long> exampleIds) {
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
        return complete(subject, sentAt, bodyText, List.of());
    }

    /**
     * Read one email with worked examples in front of it.
     *
     * <p>The examples go between the system prompt and the real question, as user/assistant pairs,
     * nearest last. Only {@link FewShotRetriever} produces them, and only when the
     * {@code parser.fewShotExamples} setting is above zero. Empty is the normal zero-shot request,
     * byte for byte the same as the three-argument call.
     */
    public Completion complete(String subject, LocalDateTime sentAt, String bodyText,
                               List<FewShotRetriever.Example> examples) {
        ModelEndpoint endpoint = settings.activeEndpoint();
        boolean cloud = props.isCloud();
        String user = userTurn(subject, sentAt, bodyText);
        // Checked before anything is built or sent: without a key the cloud call cannot succeed,
        // and the message is the thing the administrator needs to read.
        String apiKey = cloud ? requireCloudKey() : null;
        String payload = cloud
                ? cloudRequestBody(examples, user, endpoint.model())
                : requestBody(examples, user, endpoint.model());

        long started = System.currentTimeMillis();
        HttpResponse<String> response = send(endpoint, payload, apiKey);
        if (cloud) {
            response = waitOutRateLimit(endpoint, payload, apiKey, response);
        }
        int durationMs = (int) (System.currentTimeMillis() - started);

        if (response.statusCode() / 100 != 2) {
            lastError = "HTTP " + response.statusCode();
            if (cloud) {
                throw cloudRefusal(response.statusCode(), response.body());
            }
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
            int promptChars = user.length();
            for (FewShotRetriever.Example e : examples) {
                promptChars += e.user().length() + e.assistant().length();
            }
            return new Completion(message.asText(), body.path("model").asText(null),
                    durationMs, promptChars,
                    examples.stream().map(FewShotRetriever.Example::sampleId).toList());
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
        if (props.isCloud()) {
            return cloudReachable();
        }
        String health = settings.endpoint().base() + "/health";
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

    /**
     * The chat request for one question.
     *
     * <p>Package-private so a test can read the bytes the server would get. With no examples the
     * messages are exactly system then user, the request the model was measured under. Examples
     * go between them, never after the question, so the model reads the email it is asked about
     * last.
     */
    String requestBody(List<FewShotRetriever.Example> examples, String user, String model) {
        ObjectNode root = json.createObjectNode();
        // llama-server serves one model and ignores this; Ollama refuses the request without
        // it, since it can hold many. Sent only when configured, so the normal case carries
        // nothing it does not need.
        if (!model.isBlank()) {
            root.put("model", model);
        }
        addMessages(root, AnalysisAnnotationTemplates.SYSTEM_PROMPT, examples, user);
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
     * The chat request for one question, as a hosted model is sent it.
     *
     * <p>The same messages as {@link #requestBody}, with two differences, both because the finetune is
     * not what answers here. The local model learned the field names in training and has the grammar
     * enforced by llama.cpp, so a request to it carries no description of the shape. A general hosted
     * model knows neither, so the schema goes in the system prompt as text. And the format is
     * {@code json_object}, not {@code json_schema}: that is the one every OpenAI-compatible provider
     * accepts, where {@code json_schema} is supported only by some hosted models and refused by others
     * for schemas they cannot compile. The model is always named, because a hosted endpoint serves
     * many. Package-private for the same reason {@link #requestBody} is.
     */
    String cloudRequestBody(List<FewShotRetriever.Example> examples, String user, String model) {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        String system = AnalysisAnnotationTemplates.SYSTEM_PROMPT;
        if (schema != null) {
            system += "\n\nThe JSON object must follow this JSON Schema:\n" + schema;
        }
        addMessages(root, system, examples, user);
        root.put("temperature", 0);
        // The cloud's own ceiling, not maxTokens: a hosted free tier counts this figure against its
        // tokens a minute before anything is written, so the local 6,144 would be refused as too large.
        root.put("max_tokens", props.getCloudMaxTokens());
        root.put("stream", false);
        root.putObject("response_format").put("type", "json_object");
        return root.toString();
    }

    /**
     * The system turn, then the worked examples as user/assistant pairs, then the question.
     *
     * <p>Shared by both bodies so that the local request stays exactly the measured one: the field
     * order and the strings written are the ones it always had.
     */
    private void addMessages(ObjectNode root, String system, List<FewShotRetriever.Example> examples,
                             String user) {
        var messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        // The same two strings the export writes for each sample (TrainingTurns), so an example
        // in a request looks exactly like an example in the file the model was finetuned on.
        for (FewShotRetriever.Example e : examples) {
            messages.addObject().put("role", "user").put("content", e.user());
            messages.addObject().put("role", "assistant").put("content", e.assistant());
        }
        messages.addObject().put("role", "user").put("content", user);
    }

    /**
     * One POST to the model. Under the cloud, the Bearer key rides on it; nothing else about the call
     * changes, and the key is never part of a message.
     */
    private HttpResponse<String> send(ModelEndpoint endpoint, String payload, String apiKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParserUnavailableException("The parse was interrupted.", e);
        } catch (IOException e) {
            lastError = e.getMessage();
            if (apiKey != null) {
                throw new ParserUnavailableException(
                        "Could not reach the cloud model at " + endpoint.url() + " — " + e.getMessage() + ".", e);
            }
            // The common case by a wide margin, and the message has to name the cure: the
            // model server is a container on somebody's desk, and the desk gets turned off.
            throw new ParserUnavailableException(
                    "Could not reach the model at " + endpoint.url() + " — " + e.getMessage()
                            + ". Start it with: docker compose -f serve/docker-compose.llamacpp.yml"
                            + " up -d (in the chartering-ml project).", e);
        }
    }

    /**
     * Sleeps out a rate limit the provider says is short, and retries; anything longer is a stop.
     *
     * <p>A rate limit is the sweep's stop, not a failure of this email: the next sweep picks it up
     * where this one stopped, so it is thrown as {@link ParserUnavailableException}. A wait of up to
     * a minute is absorbed here, because a free tier's window is often a few seconds and stopping the
     * whole sweep for that would be the wrong size of response. A wait longer than that is the
     * provider saying "come back later", which is for the administrator to act on.
     */
    private HttpResponse<String> waitOutRateLimit(ModelEndpoint endpoint, String payload, String apiKey,
                                                  HttpResponse<String> first) {
        HttpResponse<String> response = first;
        for (int retries = 0; response.statusCode() == 429; retries++) {
            OptionalDouble wait = retryAfterSeconds(response.headers().firstValue("retry-after").orElse(null));
            lastError = "HTTP 429";
            if (wait.isEmpty() || wait.getAsDouble() > MAX_RATE_LIMIT_WAIT_SECONDS
                    || retries >= MAX_RATE_LIMIT_RETRIES) {
                throw rateLimited(wait);
            }
            // The header's figure plus a second: the header is the provider's own clock, and a retry
            // that lands a hair early is refused again and costs a whole round of the limit's window.
            pause((long) Math.ceil(wait.getAsDouble() * 1000) + 1_000);
            response = send(endpoint, payload, apiKey);
        }
        return response;
    }

    private static ParserUnavailableException rateLimited(OptionalDouble wait) {
        if (wait.isEmpty()) {
            return new ParserUnavailableException("The cloud model's rate limit is spent — try again later.");
        }
        long minutes = Math.max(1, (long) Math.ceil(wait.getAsDouble() / 60));
        return new ParserUnavailableException("The cloud model's rate limit is spent — try again in about "
                + minutes + " minute" + (minutes == 1 ? "" : "s") + ".");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParserUnavailableException("The parse was interrupted.", e);
        }
    }

    /**
     * What a cloud reply that is not a success means for the sweep.
     *
     * <p>Three different answers. A bad key stops everything, since every email would fail the same
     * way. A refusal of this email (too large, malformed) is an {@link IllegalStateException}, which
     * the sweep records against that one message and carries on from — a too-large email must not stop
     * every sweep at the same place. Anything else is unavailable, as the local server's failures are.
     */
    private static RuntimeException cloudRefusal(int status, String body) {
        return switch (status) {
            case 401, 403 -> new ParserUnavailableException(
                    "The cloud provider refused the API key (HTTP " + status + ").");
            case 400, 413, 422 -> new IllegalStateException(
                    "The cloud model refused this email (HTTP " + status + "): " + abbreviate(body));
            default -> new ParserUnavailableException(
                    "The cloud model answered HTTP " + status + ": " + abbreviate(body));
        };
    }

    private String requireCloudKey() {
        return settings.cloudApiKey().orElseThrow(() -> new ParserUnavailableException(
                "No API key for the cloud model is saved — add one on Settings › Intake parser."));
    }

    /**
     * The Retry-After header as seconds: the value when it is a non-negative number, else empty.
     *
     * <p>Only the seconds form is read. The HTTP-date form is valid but no provider here sends it,
     * and an unreadable header is treated as no header, which stops the sweep rather than guessing.
     */
    static OptionalDouble retryAfterSeconds(String header) {
        if (header == null || header.isBlank()) {
            return OptionalDouble.empty();
        }
        try {
            double seconds = Double.parseDouble(header.strip());
            return seconds >= 0 && !Double.isNaN(seconds) && !Double.isInfinite(seconds)
                    ? OptionalDouble.of(seconds)
                    : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Whether the cloud answers with the key we hold: GET the model list, which every provider serves
     * and which costs no tokens. Reachability and authentication are one question here, since a bad key
     * is the commonest way the cloud is "down".
     */
    private boolean cloudReachable() {
        Optional<String> apiKey = settings.cloudApiKey();
        if (apiKey.isEmpty()) {
            lastError = "No API key saved";
            return false;
        }
        String models = settings.cloudEndpoint().base() + "/v1/models";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(models))
                    .header("Authorization", "Bearer " + apiKey.get())
                    .timeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() / 100 == 2;
            lastError = ok ? null : "HTTP " + response.statusCode() + " from " + models;
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }
    }

    /**
     * The email as the model was trained to read it: Date, Subject, a blank line, the body.
     *
     * <p>The layout is {@link TrainingTurns#userTurn}, which {@code AnalysisExportService} also
     * uses, so the two cannot drift: the corpus was exported in this layout and the model learned
     * the layout along with the content. What is added here is only the trimming and capping of
     * the body, which is a live-request concern — the export writes what was stored.
     */
    private String userTurn(String subject, LocalDateTime sentAt, String bodyText) {
        return TrainingTurns.userTurn(subject, sentAt, trimBody(bodyText));
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
