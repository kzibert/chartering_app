package com.chartering.service.parser;

import com.chartering.config.EmbeddingProperties;
import com.chartering.service.parser.EmbeddingClient.EmbeddingUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client against a canned OpenAI-shaped server, with no Spring context.
 *
 * <p>The fake server answers each input with a vector whose single component is the number in
 * the input's text, so an order mistake across a batch boundary shows up as a wrong number rather
 * than as a silent pass.
 */
class EmbeddingClientTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> tokenizeBodies = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int status = 200;
    private int tokenizeStatus = 200;
    /** The fake tokenizer: one token per character when true, one per whitespace-separated word otherwise. */
    private boolean tokenPerChar = false;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tokenize", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            tokenizeBodies.add(body);
            byte[] reply;
            if (tokenizeStatus != 200) {
                reply = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                try {
                    String content = json.readTree(body).get("content").asText();
                    int n = tokenPerChar ? content.length()
                            : (content.isBlank() ? 0 : content.trim().split("\\s+").length);
                    reply = tokensReply(n).getBytes(StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(tokenizeStatus, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.createContext("/v1/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            byte[] reply;
            if (status != 200) {
                reply = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                reply = answer(body).getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";
    }

    private EmbeddingProperties props() {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setUrl(url());
        p.setConnectTimeout(Duration.ofSeconds(3));
        p.setReadTimeout(Duration.ofSeconds(10));
        return p;
    }

    /** A tokenizer answer with {@code n} token ids. */
    private static String tokensReply(int n) {
        return IntStream.range(0, n).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(",", "{\"tokens\":[", "]}"));
    }

    /** The text of the {@code i}th input of the {@code i}th embeddings request. */
    private String sentInput(int i) throws IOException {
        return json.readTree(requestBodies.get(i)).get("input").get(0).asText();
    }

    /** One entry per input, in input order, each vector carrying the number from its text. */
    private String answer(String requestBody) {
        try {
            JsonNode inputs = json.readTree(requestBody).get("input");
            List<String> entries = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                String text = inputs.get(i).asText();
                String digits = text.replaceAll("\\D+", "");
                int n = digits.isEmpty() ? -1 : Integer.parseInt(digits.substring(0, Math.min(digits.length(), 9)));
                entries.add("{\"object\":\"embedding\",\"index\":" + i + ",\"embedding\":[" + n + ".0,0.5]}");
            }
            return "{\"object\":\"list\",\"data\":[" + String.join(",", entries)
                    + "],\"model\":\"x\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void embedReturnsTheVectorTheServerSent() {
        EmbeddingClient client = new EmbeddingClient(props());

        float[] v = client.embed("clustering: Subject 7\n\nbody");

        assertThat(v).containsExactly(7.0f, 0.5f);
    }

    @Test
    void embedAllKeepsOrderAcrossABatchBoundary() {
        EmbeddingClient client = new EmbeddingClient(props());
        List<String> texts = IntStream.range(0, 20).mapToObj(i -> "text " + i).toList();

        List<float[]> vectors = client.embedAll(texts);

        assertThat(vectors).hasSize(20);
        for (int i = 0; i < 20; i++) {
            assertThat(vectors.get(i)[0]).isEqualTo((float) i);
        }
        // Twenty inputs at a batch of sixteen: exactly two requests, not twenty.
        assertThat(requestBodies).hasSize(2);
    }

    @Test
    void requestCarriesTheModelAndThePrefixedText() {
        EmbeddingClient client = new EmbeddingClient(props());

        client.embed(client.textFor("Cargo 9", "wheat"));

        String body = requestBodies.get(0);
        assertThat(body).contains("clustering: Cargo 9");
        assertThat(body).contains("nomic-embed-text-v1.5");
    }

    @Test
    void serverErrorFailsAtOnceWithoutRetrying() {
        status = 500;
        EmbeddingClient client = new EmbeddingClient(props());

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.embed("x"))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining(url());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).isLessThan(5_000);
        // One attempt, not the default ten.
        assertThat(requestBodies).hasSize(1);
    }

    @Test
    void closedPortFailsAtOnce() throws IOException {
        int closed;
        try (var probe = new java.net.ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        EmbeddingProperties p = props();
        p.setUrl("http://127.0.0.1:" + closed + "/v1/embeddings");
        EmbeddingClient client = new EmbeddingClient(p);

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.embed("x"))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining(p.getUrl());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void textForKeepsPrefixAndSubjectAndTruncatesTheBody() {
        EmbeddingProperties p = props();
        p.setMaxChars(60);
        EmbeddingClient client = new EmbeddingClient(p);
        String longBody = "word ".repeat(200);

        String text = client.textFor("OPEN  WHEAT\n\nSUBJECT", longBody);

        assertThat(text).hasSizeLessThanOrEqualTo(60);
        assertThat(text).startsWith("clustering: OPEN WHEAT SUBJECT\n\n");
        // The blank line after the subject is the separator; the body itself has no newlines.
        assertThat(text.substring(text.indexOf("\n\n") + 2)).doesNotContain("\n");
    }

    @Test
    void textForCollapsesWhitespaceAndHandlesNulls() {
        EmbeddingClient client = new EmbeddingClient(props());

        assertThat(client.textFor(null, null)).isEqualTo("clustering: \n\n");
        assertThat(client.textFor("a   b", "c\r\n\r\nd")).isEqualTo("clustering: a b\n\nc d");
    }

    @Test
    void hashChangesWhenThePrefixChanges() {
        EmbeddingProperties a = props();
        EmbeddingProperties b = props();
        b.setPrefix("search_document: ");

        String textA = new EmbeddingClient(a).textFor("s", "b");
        String textB = new EmbeddingClient(b).textFor("s", "b");

        assertThat(EmbeddingClient.hash(textA)).isNotEqualTo(EmbeddingClient.hash(textB));
        assertThat(EmbeddingClient.hash(textA)).hasSize(64);
        assertThat(EmbeddingClient.hash(textA)).isEqualTo(EmbeddingClient.hash(textA));
    }

    @Test
    void aTextWithinTheTokenLimitIsSentUnchangedAfterOneCount() throws IOException {
        EmbeddingClient client = new EmbeddingClient(props());

        client.embed("clustering: Cargo 9\n\nwheat to Spain");

        assertThat(tokenizeBodies).hasSize(1);
        assertThat(tokenizeBodies.get(0)).contains("\"add_special\":true");
        assertThat(sentInput(0)).isEqualTo("clustering: Cargo 9\n\nwheat to Spain");
    }

    @Test
    void aTextOverTheTokenLimitIsTrimmedFromTheEndAndKeepsItsStart() throws IOException {
        tokenPerChar = true;
        EmbeddingProperties p = props();
        p.setMaxTokens(50);
        EmbeddingClient client = new EmbeddingClient(p);
        String text = client.textFor("Subject", "alpha ".repeat(100));

        client.embed(text);

        String sent = sentInput(0);
        assertThat(sent.length()).isLessThanOrEqualTo(50);
        assertThat(sent).startsWith("clustering: Subject\n\nalpha");
        assertThat(text).startsWith(sent);
        // Counted, trimmed, counted again: the second count is what let the text through.
        assertThat(tokenizeBodies).hasSizeGreaterThanOrEqualTo(2);
        assertThat(requestBodies).hasSize(1);
    }

    @Test
    void tokenizeFailureFailsAtOnceAndNeverEmbeds() {
        tokenizeStatus = 500;
        EmbeddingClient client = new EmbeddingClient(props());

        assertThatThrownBy(() -> client.embed("x"))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining(url());
        assertThat(requestBodies).isEmpty();
    }

    @Test
    void blankUrlMeansDisabled() {
        EmbeddingProperties p = new EmbeddingProperties();
        EmbeddingClient client = new EmbeddingClient(p);

        assertThat(client.isEnabled()).isFalse();
        assertThatThrownBy(() -> client.embed("x"))
                .isInstanceOf(EmbeddingUnavailableException.class);
    }
}
