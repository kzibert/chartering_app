package com.chartering.service;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

/**
 * The parts every OpenAI-shaped Spring AI client in this application builds the same way.
 *
 * <p>Spring AI takes a server as a base URL and a path, not as one URL, and wants a key it never
 * uses; its default retry template makes around ten attempts with backoff; and its
 * {@code RestClient} reads timeouts from a request factory. {@code EmbeddingClient} and
 * {@code FeedLlmClient} need exactly those three things, so they are kept here once rather than
 * copied, and each client still chooses its own path and its own error handling. Nothing here
 * knows which request a client makes.
 */
public final class SpringAiClients {

    /** llama-server ignores the key; Spring AI refuses to build a client without one. */
    public static final String NO_KEY = "not-used";

    private SpringAiClients() {
    }

    /** A server's root and the path under it, as Spring AI's builder takes them. */
    public record Endpoint(String baseUrl, String path) {
    }

    /**
     * Splits an absolute URL into its root and its path. A URL with no path gets {@code defaultPath}.
     *
     * <p>The caller checks the scheme and host first; this only takes the URL apart.
     */
    public static Endpoint split(URI uri, String defaultPath) {
        String baseUrl = uri.getScheme() + "://" + uri.getAuthority();
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = defaultPath;
        }
        return new Endpoint(baseUrl, path);
    }

    /**
     * A {@code RestClient} builder whose timeouts are the ones given.
     *
     * <p>Timeouts go on the request factory, because that is where Spring's {@code RestClient}
     * reads them. The connect timeout is the one that matters for a server that is switched off.
     */
    public static RestClient.Builder restClient(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(connectTimeout);
        requests.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requests);
    }

    /**
     * A retry template that makes one attempt, and rethrows the failure itself.
     *
     * <p>Spring AI's default makes around ten attempts with backoff, which against a stopped server
     * is a minute of stall before the caller hears the same answer it would have heard at once.
     * Rethrowing matters too: a template that gives up wraps the last failure in
     * {@code ExhaustedRetryException}, which hides the connection error the message should name.
     */
    public static RetryTemplate noRetry() {
        RetryTemplate noRetry = RetryTemplate.builder().maxAttempts(1).build();
        noRetry.setThrowLastExceptionOnExhausted(true);
        return noRetry;
    }
}
