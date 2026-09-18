package com.chartering.service;

import java.net.URI;

/**
 * Which model server a feature talks to, and which of its two possible answers is in force.
 *
 * <p><b>The address is a runtime setting with an environment variable behind it, not one or the
 * other.</b> That shape is deliberate. A deployment still needs an answer before anybody has
 * opened the Settings tab — compose points at {@code host.docker.internal:8090} and that is what
 * a fresh install should use — while the reason to change it is never a redeploy: the general
 * model and the extraction finetune are swapped on one 8GB card, a second box gets a port of its
 * own, an Ollama is tried on an evening. So the environment variable is the default and a stored
 * row overrides it, which is this table's standing rule ({@code ParserSettings}) rather than a
 * new idea.
 *
 * <p><b>A stored row equal to the configured value is deleted rather than kept</b>, the same way
 * an unedited feed prompt has no row. It is what makes {@link #urlCustomised()} answerable at all
 * — otherwise a screen could only say "a row exists", which tells a reader nothing about whether
 * this instance is pointed somewhere unusual.
 *
 * <p><b>Both instances share one {@code app_settings} table, and that is safe here in a way it is
 * not for {@code MAIL_REPLY_PROVIDER}.</b> The hosted deployment has {@code PARSER_ENABLED} and
 * {@code FEED_ANALYSIS_ENABLED} false, so it never reads this: there is no model call to point
 * anywhere. The address only matters where the feature is switched on, which is one machine.
 *
 * @param url             the chat-completions endpoint in force
 * @param model           the model name to send, or blank to send none
 * @param configuredUrl   what the environment holds, which is what "reset" restores
 * @param configuredModel the same for the model name
 */
public record ModelEndpoint(String url, String model, String configuredUrl, String configuredModel) {

    public boolean urlCustomised() {
        return !url.equals(configuredUrl);
    }

    public boolean modelCustomised() {
        return !model.equals(configuredModel);
    }

    /** The server root, for the sibling endpoints ({@code /health}, {@code /tokenize}, {@code /props}). */
    public String base() {
        int api = url.indexOf("/v1/");
        return api > 0 ? url.substring(0, api) : url;
    }

    /**
     * Checked here rather than left to {@code URI.create} at send time.
     *
     * <p>A typo saved into the table is a feature that fails on every sweep afterwards with an
     * {@code IllegalArgumentException} from inside the HTTP client — which reads as a bug in the
     * parser rather than as a setting nobody finished typing. Refused at the point somebody can
     * still see what they typed.
     */
    public static String requireCompletionsUrl(String raw) {
        String url = raw.strip();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + url + "' is not a URL.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("The model's address must start with http:// or https:// — got '"
                    + url + "'.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("The model's address names no host: '" + url + "'.");
        }
        return url;
    }
}
