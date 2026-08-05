package com.chartering.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Strips the handful of constructs that have no business in an email body.
 *
 * <p>This is not a defence against a hostile author — the only person writing these
 * templates is the operator. It exists because mail clients strip scripting anyway, and
 * markup that <em>looks</em> like an exploit attempt is a well-known spam-filter signal.
 * Leaving an inert {@code <script>} block in a circular costs deliverability for nothing.
 */
@Component
public class HtmlSanitizer {

    private static final Pattern SCRIPT_OR_STYLE_BLOCK =
            Pattern.compile("(?is)<(script|style|iframe|object|embed)\\b.*?</\\1\\s*>");
    private static final Pattern DANGLING_TAG =
            Pattern.compile("(?is)<\\s*/?\\s*(script|iframe|object|embed|form|input|meta|link)\\b[^>]*>");
    /** on* handlers: onclick=, onload=, onerror=… with quoted or bare values. */
    private static final Pattern EVENT_HANDLER =
            Pattern.compile("(?is)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern JS_URL =
            Pattern.compile("(?is)(href|src)\\s*=\\s*([\"']?)\\s*javascript:[^\"'>\\s]*\\2");

    public String clean(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        String out = SCRIPT_OR_STYLE_BLOCK.matcher(html).replaceAll("");
        out = DANGLING_TAG.matcher(out).replaceAll("");
        out = EVENT_HANDLER.matcher(out).replaceAll("");
        out = JS_URL.matcher(out).replaceAll("$1=\"#\"");
        return out;
    }
}
