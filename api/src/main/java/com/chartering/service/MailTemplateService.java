package com.chartering.service;

import com.chartering.dto.CampaignRecipientRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mail-merge for circulars.
 *
 * <p>Personalising each message is not only a courtesy — identical bodies sent to many
 * addresses in a short window are the classic bulk-mail fingerprint, so per-recipient
 * variation genuinely helps the mail land in the inbox.
 */
@Service
public class MailTemplateService {

    /** Placeholder → what the compose tab offers as an insert button. */
    public static final Map<String, String> PLACEHOLDERS = new LinkedHashMap<>() {{
        put("greeting", "Greeting name, falling back to the person's name, then to \"Sirs\"");
        put("name", "Full person name");
        put("title", "Title (Mr/Ms/Capt...)");
        put("company", "Company name");
        put("email", "The recipient's own address");
    }};

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final String GREETING_FALLBACK = "Sirs";

    /** Substitute placeholders in the HTML body. Merged values are escaped, never interpreted. */
    public String renderHtml(String template, CampaignRecipientRequest r) {
        return render(template, r, true);
    }

    /** Substitute placeholders in a plain-text body (no escaping — it isn't markup). */
    public String renderText(String template, CampaignRecipientRequest r) {
        return render(template, r, false);
    }

    private String render(String template, CampaignRecipientRequest r, boolean escape) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            String value = valueFor(key, r);
            // An unknown placeholder is left verbatim so a typo is visible in the preview
            // instead of silently deleting a line of the circular.
            String replacement = value == null ? m.group(0) : (escape ? escapeHtml(value) : value);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String valueFor(String key, CampaignRecipientRequest r) {
        return switch (key) {
            case "greeting" -> firstNonBlank(r.getGreetingName(), r.getPersonName(), GREETING_FALLBACK);
            case "name" -> firstNonBlank(r.getPersonName(), r.getGreetingName(), "");
            case "title" -> nullToEmpty(r.getTitle());
            case "company" -> nullToEmpty(r.getCompanyName());
            case "email" -> nullToEmpty(r.getEmail());
            default -> null;
        };
    }

    /**
     * Plain-text alternative for the HTML body. Every message goes out as
     * multipart/alternative: a text part is what plain-text clients show, and its absence
     * is one of the cheapest spam signals for a filter to score against.
     */
    public String htmlToText(String html) {
        String s = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n\n")
                // Every block-level boundary — closing *and* opening — has to become a line
                // break, or content either side of a list or table runs together in the text part.
                .replaceAll("(?i)</(h[1-6]|div|tr|ul|ol|table|blockquote)\\s*>", "\n")
                .replaceAll("(?i)<(p|div|h[1-6]|tr|ul|ol|table|blockquote)[^>]*>", "\n")
                .replaceAll("(?i)<li[^>]*>", "\n  - ")
                .replaceAll("(?i)</(td|th)\\s*>", "\t");
        s = TAG.matcher(s).replaceAll("");
        s = unescapeHtml(s);
        // Collapse the blank-line pile-up left behind by nested block tags.
        return s.replaceAll("[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String unescapeHtml(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
