package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.service.feed.FeedReader.FeedFetchException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one way the Feed reaches another server.
 *
 * <p>{@code java.net.http} rather than Jsoup's own connection, for the reason the parser client
 * gives: two timeouts, not one. A source whose host is down should fail in seconds; a feed that
 * is merely slow to render a long article should be given the time.
 *
 * <p>Conditional GET is built in because most of these pages change a few times a day and are
 * fetched every hour. ship.gr sends an ETag and a Last-Modified; RSS servers generally do too.
 */
@Component
@RequiredArgsConstructor
public class FeedHttp {

    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w.-]+)", Pattern.CASE_INSENSITIVE);

    private final FeedProperties props;

    private HttpClient client;

    private HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return client;
    }

    /**
     * @param notModified true on a 304; {@code body} is then null
     */
    public record Page(String body, String finalUrl, String etag, String lastModified,
                       boolean notModified) {
    }

    public Page get(String url) {
        return get(url, null, null);
    }

    public Page get(String url, String etag, String lastModified) {
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw new FeedFetchException("Not a usable address: " + url, e);
        }
        request.timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .header("User-Agent", props.getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/rss+xml,"
                        + "application/atom+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET();
        if (etag != null && !etag.isBlank()) request.header("If-None-Match", etag);
        if (lastModified != null && !lastModified.isBlank()) {
            request.header("If-Modified-Since", lastModified);
        }

        HttpResponse<byte[]> response;
        try {
            response = client().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedFetchException("Interrupted while reading " + url, e);
        } catch (IOException e) {
            throw new FeedFetchException("Could not reach " + host(url) + ": " + e.getMessage(), e);
        }

        String newEtag = response.headers().firstValue("ETag").orElse(null);
        String newModified = response.headers().firstValue("Last-Modified").orElse(null);
        if (response.statusCode() == 304) {
            return new Page(null, url, etag, lastModified, true);
        }
        if (response.statusCode() / 100 != 2) {
            // Named plainly, 403 especially: it is the site saying no, and the fix is to drop
            // the source rather than to try harder.
            throw new FeedFetchException(host(url) + " answered HTTP " + response.statusCode()
                    + (response.statusCode() == 403 ? " (the site refuses this reader)" : ""));
        }
        Charset charset = response.headers().firstValue("Content-Type")
                .map(CHARSET::matcher)
                .filter(Matcher::find)
                .map(m -> {
                    try {
                        return Charset.forName(m.group(1));
                    } catch (Exception e) {
                        return StandardCharsets.UTF_8;
                    }
                })
                .orElse(StandardCharsets.UTF_8);
        return new Page(new String(response.body(), charset), response.uri().toString(),
                newEtag, newModified, false);
    }

    /** The courtesy gap between two requests to one site within a fetch. */
    public void pause() {
        try {
            Thread.sleep(props.getRequestGapMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedFetchException("Interrupted between requests", e);
        }
    }

    static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
