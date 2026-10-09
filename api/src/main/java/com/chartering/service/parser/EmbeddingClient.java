package com.chartering.service.parser;

import com.chartering.config.EmbeddingProperties;
import com.chartering.service.SpringAiClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.net.URI;
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
 * the prefix and the truncation as well as the words, so changing either makes every stored vector
 * mismatch and the indexer re-embeds the corpus. That is the intended behaviour, not a side effect:
 * a vector computed under another prefix is in another space and must not be compared.
 */
@Component
@Slf4j
public class EmbeddingClient {

    /**
     * Inputs per request. The server accepts more; sixteen keeps one request's CPU time short
     * enough that the read timeout is a failure rather than a slow batch.
     */
    static final int BATCH_SIZE = 16;

    private static final String DEFAULT_PATH = "/v1/embeddings";

    private final EmbeddingProperties props;

    /** Built on first use. The URL is an environment setting, so it cannot change under it. */
    private volatile OpenAiEmbeddingModel model;

    public EmbeddingClient(EmbeddingProperties props) {
        this.props = props;
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
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
            out.addAll(callOnce(batch));
        }
        return out;
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

    private OpenAiEmbeddingModel build() {
        URI uri;
        try {
            uri = URI.create(props.getUrl().trim());
        } catch (IllegalArgumentException e) {
            throw unavailable("EMBEDDING_URL is not a URL", e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw unavailable("EMBEDDING_URL is not an absolute URL", null);
        }

        // Spring AI takes the server as a base and a path, not as one URL.
        SpringAiClients.Endpoint endpoint = SpringAiClients.split(uri, DEFAULT_PATH);

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
