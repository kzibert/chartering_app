package com.chartering.service.feed;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Text as the summariser wants it: the words, the line breaks that carry a circular's layout,
 * and nothing else.
 *
 * <p>Line breaks are kept on purpose. A position list is a table typed with spaces and
 * newlines — "M/V CELINE 5,100 DWCC / 21-24 SEPT DURRES" one ship a line — and flattening it
 * into one paragraph would hand the model a run-on sentence of ship names and dates.
 */
public final class FeedText {

    private FeedText() {
    }

    /** An HTML fragment or page as plain text, with block and {@code <br>} boundaries kept. */
    public static String fromHtml(String html) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parseBodyFragment(html);
        return fromElement(doc.body());
    }

    private static final Set<String> BLOCKS = Set.of("p", "div", "li", "ul", "ol", "tr", "table", "h1", "h2",
            "h3", "h4", "h5", "h6", "blockquote", "dt", "dd", "dl", "section", "article", "header", "footer", "pre");

    /**
     * The same for an element already parsed out of a page.
     *
     * <p>Walked node by node rather than serialised and cleaned, because that is how a browser
     * lays text out: whitespace in the source — including the newline a board's HTML happens to
     * have after each {@code <br>} — is a space, and only a {@code <br>} or a block boundary is a
     * line break. Treating both as breaks put a blank line under every line of a circular.
     */
    public static String fromElement(Element element) {
        if (element == null) return "";
        Element copy = element.clone();
        copy.select("script, style, noscript, svg, ins").remove();
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    sb.append(text.getWholeText().replaceAll("[ \\t\\r\\n\\f]+", " "));
                } else if (node instanceof Element e) {
                    if (e.normalName().equals("br") || BLOCKS.contains(e.normalName())) sb.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e && BLOCKS.contains(e.normalName())) sb.append('\n');
            }
        }, copy);
        return normalise(sb.toString());
    }

    /**
     * Spaces collapsed within lines, lines trimmed, runs of blank lines cut to one.
     *
     * <p>Non-breaking spaces are spaces here: ShipOffer's offers are Outlook HTML padded out
     * with dozens of them to line columns up, and each one is a token the window pays for.
     */
    public static String normalise(String text) {
        if (text == null) return "";
        String s = text.replace(' ', ' ').replace("\r\n", "\n").replace('\r', '\n')
                .replace("﻿", "").replace("​", "");
        StringBuilder out = new StringBuilder(s.length());
        int blank = 0;
        for (String line : s.split("\n", -1)) {
            String trimmed = line.replaceAll("[ \\t\\x0B\\f]+", " ").strip();
            if (trimmed.isEmpty()) {
                blank++;
                if (blank > 1) continue;
            } else {
                blank = 0;
            }
            out.append(trimmed).append('\n');
        }
        return out.toString().strip();
    }

    /**
     * A fingerprint of the words alone — case, spacing and line breaks ignored — so the same
     * circular pasted onto two boards, or reposted with a different indent, hashes the same.
     */
    public static String hash(String text) {
        String key = normalise(text).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
