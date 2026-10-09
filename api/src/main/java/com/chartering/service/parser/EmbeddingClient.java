package com.chartering.service.parser;

import com.chartering.config.EmbeddingProperties;
import com.chartering.service.SpringAiClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Turns an email into the vector the retrieval step compares it by.
 *
 * <p>An OpenAI-shaped {@code /v1/embeddings} call, through Spring AI's {@link OpenAiEmbeddingModel}
 * rather than a hand-written client: the request and response shapes are the library's, and the
 * part that is ours is the text put into them and what to do when the server does not answer.
 *
 * <p><b>Built by hand, not by the starter.</b> {@code spring-ai-openai} is on the classpath without
 * the autoconfigure module, so nothing creates a model at startup. The address is read from
 * {@link EmbeddingProperties} when the first vector is wanted, and the application starts with no
 * embedding server at all - the same reason {@code EmailParserClient} exists rather than a bean
 * of its own.
 *
 * <p><b>No retries, on purpose.</b> Spring AI's default retry template makes around ten attempts
 * with backoff. Against a stopped server that is a minute of stall in front of every parse, and
 * the caller's answer is the same after the first refusal: carry on with no examples. So the
 * template here makes one attempt and the failure comes back at once.
 *
 * <p><b>The text embedded is {@link #textFor}, and the hash is {@link #hash}.</b> The hash covers
 * the prefix, the subject and the character truncation, so changing any of them makes every stored
 * vector mismatch and the indexer re-embeds the corpus. That is the intended behaviour, not a side
 * effect: a vector computed under another prefix is in another space and must not be compared.
 * The hash is of the text before the token fit below; the fit is a function of that text and
 * {@code maxTokens}, so changing {@code maxTokens} or the server means re-indexing for the same
 * reason.
 *
 * <p><b>Fitted in tokens, not only in characters.</b> {@code maxChars} bounds what is hashed and
 * sent, but the model reads 2,048 tokens and a circular dense with figures runs about two
 * characters a token - one real one was 2,908 tokens at 6,000 characters, and the server refused
 * it. So before sending, the server's own {@code /tokenize} counts each text and the tail is
 * trimmed until it fits. Trimmed rather than refused, because the densest circulars are the ones
 * most worth retrieving: a refusal would leave exactly those without vectors. The prefix and the
 * subject are at the start and are kept; the end is what goes.
 */
@Component
@Slf4j
public class EmbeddingClient {

    /**
     * Inputs per request. The server accepts more; sixteen keeps one request's CPU time short
     * enough that the read timeout is a failure rather than a slow batch.
     */
    static final int BATCH_SIZE = 16;

    /**
     * Rounds of trimming before a text is refused. Each round aims a little under the budget
     * (the 0.95 factor), so one round normally lands; four is for a tokenizer that counts more
     * per character than the last round measured.
     */
    private static final int FIT_ROUNDS = 4;

    private static final String DEFAULT_PATH = "/v1/embeddings";

    private final EmbeddingProperties props;

    /** For {@code /tokenize} only. The embeddings call goes through {@link #model}. */
    private final HttpClient http;

    private final ObjectMapper json = new ObjectMapper();

    /** Built on first use. The URL is an environment setting, so it cannot change under it. */
    private volatile OpenAiEmbeddingModel model;

    public EmbeddingClient(EmbeddingProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(props.getConnectTimeout()).build();
    }

    /**
     * Failure to get vectors from the server: refused, timed out, an HTTP error, or an answer with
     * no vector in it. The message names the URL so the log says which server was asked.
     */
    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public EmbeddingUnavailableException(String message) {
            super(message);
        }
    }

    /** Whether retrieval is switched on for this deployment at all. */
    public boolean isEnabled() {
        return !props.getUrl().isBlank();
    }

    /** The name the vectors are stored under. Vectors from two models are never compared. */
    public String model() {
        return props.getModel();
    }

    /**
     * The exact string that is embedded: the prefix, the subject, a blank line, then the body.
     *
     * <p>Whitespace runs are collapsed, because a quoted chain's hard wraps carry no meaning and
     * would otherwise cost characters of the budget. Truncation takes the body, never the prefix
     * or the subject: a position list's opening is what says what kind of email it is, and a
     * subject cut in half says nothing.
     */
    public String textFor(String subject, String body) {
        String head = props.getPrefix() + collapse(subject) + "\n\n";
        String text = collapse(body);
        int max = props.getMaxChars();
        if (head.length() >= max) {
            return cut(head, max);
        }
        if (head.length() + text.length() <= max) {
            return head + text;
        }
        int room = max - head.length();
        // Do not leave half of a surrogate pair at the end of the text.
        if (room > 0 && Character.isHighSurrogate(text.charAt(room - 1))) {
            room--;
        }
        return head + text.substring(0, room);
    }

    /**
     * SHA-256 of the UTF-8 text, as lowercase hex. Stored beside each vector so the indexer can
     * tell a vector is still for the text it would embed today.
     */
    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256; this is not a condition to handle.
            throw new IllegalStateException(e);
        }
    }

    /** One vector for one text. */
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /**
     * One vector per input, in the order given. Sent in batches of {@link #BATCH_SIZE}; the
     * server's own {@code index} is used to put each answer back in its place, so a server that
     * returns its data out of order still lines up.
     */
    public List<float[]> embedAll(List<String> texts) {
        List<String> fitted = new ArrayList<>(texts.size());
        for (String text : texts) {
            fitted.add(fit(text));
        }
        List<float[]> out = new ArrayList<>(fitted.size());
        for (int from = 0; from < fitted.size(); from += BATCH_SIZE) {
            List<String> batch = fitted.subList(from, Math.min(from + BATCH_SIZE, fitted.size()));
            out.addAll(callOnce(batch));
        }
        return out;
    }

    /**
     * The text trimmed from its end until the server's tokenizer counts it within
     * {@link EmbeddingProperties#getMaxTokens}. Returned unchanged when it already fits.
     *
     * <p>Each round scales the length by the ratio of the budget to the count, under-shooting by
     * 5% so the usual case is one round. The cut keeps the start: the prefix and the subject are
     * what say what kind of email it is. Counting goes to the same server that will embed, so if
     * the count cannot be had the embedding cannot either, and that failure is not hidden behind
     * an estimate.
     */
    String fit(String text) {
        int max = props.getMaxTokens();
        int tokens = count(text);
        if (tokens <= max) {
            return text;
        }
        int charsBefore = text.length();
        int tokensBefore = tokens;
        String fitted = text;
        for (int round = 0; round < FIT_ROUNDS && tokens > max; round++) {
            int newLen = (int) (fitted.length() * (max / (double) tokens) * 0.95);
            fitted = cut(fitted, newLen);
            tokens = count(fitted);
        }
        if (tokens > max) {
            throw unavailable("could not fit a text into " + max + " tokens", null);
        }
        log.debug("Trimmed embedding input from {} to {} chars ({} to {} tokens)",
                charsBefore, fitted.length(), tokensBefore, tokens);
        return fitted;
    }

    /**
     * Tokens in a text, counted by the server's own tokenizer with its special tokens. Any failure
     * to count is an {@link EmbeddingUnavailableException}: no estimate stands in for it.
     */
    int count(String text) {
        if (!isEnabled()) {
            throw new EmbeddingUnavailableException("embedding server is not configured "
                    + "(EMBEDDING_URL is blank)");
        }
        String tokenizeUrl = SpringAiClients.split(serverUri(), DEFAULT_PATH).baseUrl() + "/tokenize";
        try {
            ObjectNode body = json.createObjectNode().put("content", text).put("add_special", true);
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenizeUrl))
                    .header("Content-Type", "application/json")
                    .timeout(props.getReadTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw unavailable("could not count tokens: HTTP " + response.statusCode(), null);
            }
            JsonNode tokens = json.readTree(response.body()).path("tokens");
            if (!tokens.isArray()) {
                throw unavailable("answered /tokenize with no tokens array", null);
            }
            return tokens.size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("was interrupted counting tokens", e);
        } catch (IOException | IllegalArgumentException e) {
            throw unavailable("could not count tokens", e);
        }
    }

    private List<float[]> callOnce(List<String> batch) {
        EmbeddingResponse response;
        try {
            response = openAiModel().call(new EmbeddingRequest(batch, options()));
        } catch (EmbeddingUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // Spring AI's own exceptions, RestClient's connection failures and HTTP errors all
            // arrive here. None of them is a type the caller should have to know about.
            throw unavailable("could not get vectors", e);
        }

        List<Embedding> results = response == null ? List.of() : response.getResults();
        if (results == null || results.size() != batch.size()) {
            throw unavailable("answered with " + (results == null ? 0 : results.size())
                    + " vectors for " + batch.size() + " inputs", null);
        }
        List<Embedding> ordered = new ArrayList<>(results);
        ordered.sort(Comparator.comparingInt(Embedding::getIndex));

        List<float[]> vectors = new ArrayList<>(ordered.size());
        for (Embedding e : ordered) {
            float[] v = e.getOutput();
            if (v == null || v.length == 0) {
                throw unavailable("answered with an empty vector", null);
            }
            vectors.add(v);
        }
        return vectors;
    }

    private OpenAiEmbeddingOptions options() {
        return OpenAiEmbeddingOptions.builder().model(props.getModel()).build();
    }

    /**
     * The model, built once. Double-checked so two first callers do not each build one; the
     * volatile read is the common path and costs nothing after that.
     */
    private OpenAiEmbeddingModel openAiModel() {
        if (!isEnabled()) {
            throw new EmbeddingUnavailableException("embedding server is not configured "
                    + "(EMBEDDING_URL is blank)");
        }
        OpenAiEmbeddingModel m = model;
        if (m == null) {
            synchronized (this) {
                m = model;
                if (m == null) {
                    m = build();
                    model = m;
                }
            }
        }
        return m;
    }

    /** The configured URL, checked to be absolute. Shared by the model and the tokenizer call. */
    private URI serverUri() {
        URI uri;
        try {
            uri = URI.create(props.getUrl().trim());
        } catch (IllegalArgumentException e) {
            throw unavailable("EMBEDDING_URL is not a URL", e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw unavailable("EMBEDDING_URL is not an absolute URL", null);
        }
        return uri;
    }

    private OpenAiEmbeddingModel build() {
        // Spring AI takes the server as a base and a path, not as one URL.
        SpringAiClients.Endpoint endpoint = SpringAiClients.split(serverUri(), DEFAULT_PATH);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(SpringAiClients.NO_KEY)
                .embeddingsPath(endpoint.path())
                .restClientBuilder(SpringAiClients.restClient(props.getConnectTimeout(), props.getReadTimeout()))
                .build();

        // One attempt, rethrown at once. See SpringAiClients.noRetry for why the default is wrong here.
        log.info("Embedding client for {} (model {})", props.getUrl(), props.getModel());
        return new OpenAiEmbeddingModel(api, MetadataMode.NONE, options(), SpringAiClients.noRetry());
    }

    private EmbeddingUnavailableException unavailable(String what, Throwable cause) {
        String detail = cause == null ? "" : ": " + (cause.getMessage() != null
                ? cause.getMessage() : cause.getClass().getSimpleName());
        return new EmbeddingUnavailableException(
                "Embedding server at " + props.getUrl() + " " + what + detail, cause);
    }

    private static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String cut(String s, int max) {
        if (max <= 0) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
