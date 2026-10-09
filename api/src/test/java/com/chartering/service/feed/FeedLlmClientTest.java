package com.chartering.service.feed;

import com.chartering.config.ParserProperties;
import com.chartering.service.ModelEndpoint;
import com.chartering.service.ParserSettings;
import com.chartering.service.feed.FeedLlmClient.Completion;
import com.chartering.service.feed.FeedLlmClient.ModelUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Feed's chat call against a canned OpenAI-shaped server, with no Spring context.
 *
 * <p>The settings are mocked: the client only asks them for the address and the model name, and
 * the test changes the answer to show the next call goes to the new address. Each failure is
 * checked for its exception type, its message and the number of requests made - a retry would
 * show as a second request, and a stall as the elapsed time.
 */
class FeedLlmClientTest {

    /** A reply in the shape llama-server returns, with usage, for one question. */
    private static final String REPLY = """
            {"id":"x","object":"chat.completion","model":"qwen-general",
             "choices":[{"index":0,"message":{"role":"assistant","content":"  Rates hold at $12.50/t.  "},
                         "finish_reason":"stop"}],
             "usage":{"prompt_tokens":123,"completion_tokens":45,"total_tokens":168}}
            """;

    /** One canned server: what it was asked, and how it answers. */
    private static final class Canned {
        final List<String> bodies = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile String reply = REPLY;
        volatile long delayMs = 0;
        HttpServer server;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                try {
                    if (delayMs > 0) Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] out = (status == 200 ? reply : "{\"error\":\"boom\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(out);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        }

        void stop() {
            server.stop(0);
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final Canned first = new Canned();
    private final Canned second = new Canned();
    private final FeedSettings settings = mock(FeedSettings.class);
    private final ParserSettings parser = mock(ParserSettings.class);
    private ParserProperties props;
    private FeedLlmClient client;

    @BeforeEach
    void start() throws IOException {
        first.start();
        second.start();
        props = new ParserProperties();
        props.setReadTimeoutMs(10_000);
        client = new FeedLlmClient(props, settings, parser, json);
        point(first.url(), "qwen-general");
    }

    @AfterEach
    void stop() {
        first.stop();
        second.stop();
    }

    private void point(String url, String model) {
        when(settings.endpoint()).thenReturn(new ModelEndpoint(url, model, url, model));
    }

    private JsonNode lastRequest() throws IOException {
        return json.readTree(first.bodies.get(first.bodies.size() - 1));
    }

    @Test
    void requestCarriesTheMessagesOptionsAndConfiguredModel() throws IOException {
        client.chat("SYSTEM TEXT", "USER TEXT", 300);

        JsonNode request = lastRequest();
        assertThat(request.path("model").asText()).isEqualTo("qwen-general");
        assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo("SYSTEM TEXT");
        assertThat(request.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(request.path("messages").get(1).path("content").asText()).isEqualTo("USER TEXT");
        assertThat(request.path("temperature").asDouble()).isZero();
        assertThat(request.path("max_tokens").asInt()).isEqualTo(300);
        assertThat(request.path("stream").asBoolean(true)).isFalse();
        assertThat(request.has("max_completion_tokens")).isFalse();
    }

    @Test
    void contentAndTokenCountsComeBack() {
        Completion c = client.chat("s", "u", 300);

        assertThat(c.content()).isEqualTo("Rates hold at $12.50/t.");
        assertThat(c.model()).isEqualTo("qwen-general");
        assertThat(c.promptTokens()).isEqualTo(123);
        assertThat(c.completionTokens()).isEqualTo(45);
        assertThat(c.durationMs()).isBetween(0, 5_000);
    }

    @Test
    void blankModelIsSentAsThePlaceholderNotAsOpenAiDefault() throws IOException {
        point(first.url(), "");

        client.chat("s", "u", 300);

        JsonNode request = lastRequest();
        assertThat(request.path("model").asText()).isEqualTo("local");
        assertThat(lastRequestText()).doesNotContain("gpt-4o-mini");
    }

    private String lastRequestText() {
        return first.bodies.get(first.bodies.size() - 1);
    }

    @Test
    void serverErrorFailsAtOnceWithItsStatusAndNoRetry() {
        first.status = 500;

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> client.chat("s", "u", 300))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("The model answered HTTP 500")
                .hasMessageContaining("boom");
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
        assertThat(first.bodies).hasSize(1);
    }

    @Test
    void closedPortFailsAtOnceNamingTheAddress() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = free.getLocalPort();
        }
        String url = "http://127.0.0.1:" + port + "/v1/chat/completions";
        point(url, "qwen-general");

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> client.chat("s", "u", 300))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("Could not reach the model at " + url)
                .hasMessageContaining("Start it in chartering-ml");
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
    }

    @Test
    void readTimeoutFailsWithoutRetrying() {
        props.setReadTimeoutMs(500);
        first.delayMs = 1_500;

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> client.chat("s", "u", 300))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("Could not reach the model at");
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
        assertThat(first.bodies).hasSize(1);
    }

    @Test
    void replyWithoutTextIsRefused() {
        first.reply = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"}}]}";

        assertThatThrownBy(() -> client.chat("s", "u", 300))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("carried no text");
    }

    @Test
    void aChangedAddressIsUsedOnTheNextCall() {
        client.chat("s", "u", 300);
        assertThat(first.bodies).hasSize(1);

        point(second.url(), "qwen-general");
        Completion c = client.chat("s", "u", 300);

        assertThat(c.content()).isEqualTo("Rates hold at $12.50/t.");
        assertThat(second.bodies).hasSize(1);
        assertThat(first.bodies).hasSize(1);
    }
}
