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
        put("salutation", "The whole opening line: \"Dear Michael\", or \"Good day\" when no "
                + "name is known. Put it where the greeting goes, with no \"Dear\" in front.");
        put("greeting", "Greeting name only, falling back to the person's name, then to \"Sirs\"");
        put("name", "Full person name");
        put("title", "Title (Mr/Ms/Capt...)");
        put("company", "Company name");
        put("email", "The recipient's own address");
    }};

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final String GREETING_FALLBACK = "Sirs";

    /** What {@code salutation} puts in front of a name it has. */
    private static final String SALUTATION_PREFIX = "Dear ";

    /**
     * The opening for a recipient with no name on file: a company-wide desk, or one of the
     * people whose greeting was never filled in.
     *
     * <p>Chosen to be the one opener that is right in every direction a circular goes at
     * once. It does not commit to a number, so it fits both one broker and a
     * {@code chartering@} inbox that three people read; it does not commit to a gender,
     * which "Sirs" does wrongly for a good part of the book; and it does not commit to a
     * relationship, so it reads the same to an owner, a charterer, a broker or an agent.
     * It is also already this app's own register — see
     * {@code SettingsService.DEFAULT_WHATSAPP_MESSAGE}.
     */
    private static final String NEUTRAL_SALUTATION = "Good day";

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
            case "salutation" -> salutation(r);
            case "greeting" -> firstNonBlank(r.getGreetingName(), r.getPersonName(), GREETING_FALLBACK);
            case "name" -> firstNonBlank(r.getPersonName(), r.getGreetingName(), "");
            case "title" -> nullToEmpty(r.getTitle());
            case "company" -> nullToEmpty(r.getCompanyName());
            case "email" -> nullToEmpty(r.getEmail());
            default -> null;
        };
    }

    /**
     * The whole opening line, rather than a word to drop after a hardcoded "Dear".
     *
     * <p>{@code Dear {@literal {{greeting}}}} cannot be made to work for everyone, and the
     * reason is grammatical rather than a matter of picking a better word: whatever fills the
     * blank has to be a noun, and every noun that fits is either singular or plural, and most
     * are gendered. "Sirs" misgenders; "Sir or Madam" is singular at a desk several people
     * read; "All" is plural at one unnamed broker. Letting the placeholder own the "Dear"
     * removes the blank instead of trying to fill it — the word only appears when there is a
     * name for it to attach to, and otherwise the line is just {@link #NEUTRAL_SALUTATION}.
     *
     * <p>No title is added. The template in use today sends "Dear Levent", not "Dear Capt.
     * Levent", and quietly changing the tone of every named circular is not this
     * placeholder's job — {@code title} is still there for anyone who wants it.
     */
    private static String salutation(CampaignRecipientRequest r) {
        String name = firstNonBlank(r.getGreetingName(), r.getPersonName());
        return name.isEmpty() ? NEUTRAL_SALUTATION : SALUTATION_PREFIX + name;
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
