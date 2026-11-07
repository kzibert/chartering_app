package com.chartering.service.ask;

import com.chartering.config.ParserProperties;
import com.chartering.service.ModelEndpoint;
import com.chartering.service.ParserSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * The answering call to the model server, for "Ask the desk".
 *
 * <p><b>Hand-written, like {@code EmailParserClient}, and for the same reason.</b> The chat call
 * needs a field Spring AI's request model has no place for: {@code lora}, which selects which
 * adapters the server applies, and at what strength. Spring AI cannot send an unknown field, and the
 * parser's client already writes its own request for the same server, so this one does too.
 *
 * <p><b>The server holds the base model with the parser as a LoRA adapter that is on by default.</b>
 * Every parse therefore runs through the extraction finetune. An answer must be prose, so the request
 * asks for adapter 0 at scale 0: the base model with the adapter switched off, which is the model the
 * question should be asked of. A request that omitted {@code lora} would get the finetune and answer
 * in the extraction format, which is not an answer.
 *
 * <p><b>Where the server has no adapter to switch.</b> A server started without the LoRA compose file
 * has no adapter 0, and rejects the request with a message naming one of the words checked in
 * {@link #reply}. That is reported as the configuration it is, with the file to start, rather than as
 * a generic failure, because the person asking cannot fix it and the operator can in one command.
 *
 * <p>The address is {@link ParserSettings#endpoint()}, read per request for the same reason the
 * parser's is: the extraction server and the Feed's model are swapped on one card, and a question
 * asked after a swap must reach the server that is up.
 */
@Component
@Slf4j
public class AskModelClient {

    /** Long enough for a short answer over eight passages, short enough that a stalled model is a message. */
    static final int MAX_TOKENS = 700;

    /** Low, so the answer repeats the sources rather than rephrasing them. */
    static final double TEMPERATURE = 0.2;

    static final String ADAPTER_MESSAGE = "Ask the desk needs the parser served with its adapter. "
            + "The model server has no adapter to answer with, so it cannot be asked a question. Start it "
            + "with the LoRA compose file: docker compose -f serve/docker-compose.llamacpp.lora.yml up -d "
            + "(in the chartering-ml project).";

    private final ParserProperties props;
    private final ParserSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;

    public AskModelClient(ParserProperties props, ParserSettings settings, ObjectMapper json) {
        this.props = props;
        this.settings = settings;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    /** The model name the server was asked for, or null where none is configured. For the response. */
    public String modelName() {
        String model = settings.endpoint().model();
        return model == null || model.isBlank() ? null : model;
    }

    /**
     * One answer to one question.
     *
     * @throws AskUnavailableException when the server cannot be reached, answers with an error, has no
     *                                 adapter to serve the request with, or answers with no text
     */
    public String complete(String system, String user) {
        ModelEndpoint endpoint = settings.endpoint();
        String payload = requestBody(endpoint.model(), system, user);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AskUnavailableException("The question was interrupted.", e);
        } catch (IOException e) {
            throw new AskUnavailableException("Could not reach the model at " + endpoint.url() + " - "
                    + e.getMessage() + ". Start it with: docker compose -f serve/docker-compose.llamacpp.lora.yml"
                    + " up -d (in the chartering-ml project).", e);
        }
        return reply(response.statusCode(), response.body());
    }

    /**
     * The chat request for one question. Package-private so a test reads the bytes the server gets.
     *
     * <p>Messages are exactly the system rules and the user turn: no examples, no earlier turns. The
     * answer is not a schema, so {@code response_format} is not sent.
     */
    String requestBody(String model, String system, String user) {
        ObjectNode root = json.createObjectNode();
        // Sent only when configured: llama-server ignores it, and Ollama refuses a request without it.
        if (model != null && !model.isBlank()) {
            root.put("model", model);
        }
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        root.put("temperature", TEMPERATURE);
        root.put("max_tokens", MAX_TOKENS);
        root.put("stream", false);
        // Adapter 0 at scale 0: the base model, with the parser's finetune switched off. See the class
        // comment for why an answer must not come from the finetune.
        root.putArray("lora").addObject().put("id", 0).put("scale", 0);
        return root.toString();
    }

    /**
     * The answer from the server's reply, or the reason there is none.
     *
     * <p>An error whose text names the adapter, the LoRA or an invalid id is the missing-adapter case,
     * reported as {@link #ADAPTER_MESSAGE}. Any other error is reported with what the server said,
     * shortened, so the cause shows in the message rather than in a log.
     */
    String reply(int status, String body) {
        if (status / 100 != 2) {
            String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
            if (lower.contains("lora") || lower.contains("adapter") || lower.contains("invalid id")) {
                throw new AskUnavailableException(ADAPTER_MESSAGE);
            }
            throw new AskUnavailableException("The model answered HTTP " + status + ": " + abbreviate(body));
        }
        JsonNode content;
        try {
            content = json.readTree(body).path("choices").path(0).path("message").path("content");
        } catch (IOException e) {
            throw new AskUnavailableException("The model's reply was not JSON: " + abbreviate(body), e);
        }
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new AskUnavailableException("The model's reply carried no text: " + abbreviate(body));
        }
        return content.asText().strip();
    }

    /** Enough of a bad reply to recognise it, without putting a page of it in a message. */
    private static String abbreviate(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
