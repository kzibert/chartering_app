package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The embedding server: the vectors that let the parser look up the corpus samples most like an
 * incoming email.
 *
 * <p><b>Retrieval is off until a URL is set, and that is the whole switch.</b> An empty
 * {@link #url} means nothing in this application calls an embedding server at all, so a
 * deployment that has not started one is unaffected. The model is a separate process from the
 * extraction model and from the Feed's general model: it is a CPU server from the sibling
 * {@code chartering-ml} project ({@code make serve-embed}), because nomic-embed-text is small
 * enough that a GPU would be spent on it for no gain, and a card that is already swapped between
 * two 4B models has no room for a third.
 *
 * <p><b>The model and the prefix are part of the stored key.</b> Every vector is written under
 * {@link #model} with a hash of the exact text it was computed from, which covers the prefix. So
 * a change to either makes every stored vector stale and the indexer re-embeds the corpus. Vectors
 * from two models are never compared, so changing the model is a re-index rather than a silent
 * mix of two spaces.
 *
 * <p>Like {@link ParserProperties}, these are facts about the deployment rather than knobs: the
 * address of the server and what the model is called. Nothing here is a runtime setting.
 */
@Component
@ConfigurationProperties(prefix = "chartering.embedding")
@Data
public class EmbeddingProperties {

    /**
     * The full embeddings endpoint, OpenAI-shaped: {@code http://host:8092/v1/embeddings}.
     *
     * <p>Empty means retrieval is off. Compose supplies the {@code host.docker.internal} default
     * the same way it does for {@code PARSER_URL}.
     */
    private String url = "";

    /**
     * The name the vectors are stored under, and sent to the server.
     *
     * <p>llama-server serves one model and ignores the name; it is kept because it is the key
     * {@code SampleEmbeddingStore} reads vectors back by.
     */
    private String model = "nomic-embed-text-v1.5";

    /**
     * The task prefix nomic-embed-text requires on every input.
     *
     * <p>{@code clustering: } is the task for symmetric similarity - email against email, where
     * neither side is a query and neither a document. The model was trained with one of a fixed
     * set of prefixes, and an input without one gets an embedding in a space it was not trained
     * for: it still returns a vector, so nothing fails, and the nearest neighbours are just worse.
     */
    private String prefix = "clustering: ";

    /**
     * How much of the text is embedded, in characters.
     *
     * <p>The server runs with a 2,048-token context. A position list can be ten times that, and
     * its opening is what says what kind of email it is, so the body is cut and the prefix and
     * subject are kept whole.
     */
    private int maxChars = 6_000;

    /**
     * The most tokens one input may be, counted by the server's own tokenizer with its special
     * tokens included.
     *
     * <p>nomic-embed-text was trained with a 2,048-token window and must run at that size, not
     * stretched, so the server is started with {@code -c 2048 -ub 2048} (chartering-ml's
     * {@code serve/docker-compose.embed.yml}). This must not exceed that, or the server refuses
     * the input. Circulars dense with figures run about two characters a token, so
     * {@link #maxChars} alone does not keep an input in range: the client counts each text and
     * trims its tail to this before sending.
     *
     * <p>Changing it changes what is embedded for a long email, so the stored vectors must be
     * re-indexed after a change. The hash does not cover it, the same as changing the server.
     */
    private int maxTokens = 2048;

    /** Short, for the same reason as the parser's: a server that is off should fail at once. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * Generous enough for a batch. Embedding sixteen 6,000-character texts on CPU is seconds, not
     * the milliseconds a GPU would take, and a timeout here is a retrieval that silently finds
     * nothing.
     */
    private Duration readTimeout = Duration.ofSeconds(30);
}
