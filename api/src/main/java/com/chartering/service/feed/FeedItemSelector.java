package com.chartering.service.feed;

import com.chartering.model.FeedItem;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of the collected items are worth a topic's share of the context window, best first.
 *
 * <p>The cheap half of fitting a window: everything this rules out costs no tokens at all. Three
 * cuts, in order of how sure they are:
 * <ul>
 *   <li><b>Duplicates.</b> The same circular reaches two boards and a Telegram channel; the same
 *       exact words hash the same, and a repost with a different greeting still has the same
 *       opening two hundred characters of substance.
 *   <li><b>Noise.</b> Signature blocks, phone and email lines and long URLs are a third of a
 *       pasted circular and say nothing about the market.
 *   <li><b>Relevance.</b> An item has to mention one of the topic's keywords. Scored by how often,
 *       a hit in the title counting double, and discounted by age — a rate from yesterday is
 *       worth more of the window than the same rate from last Tuesday.
 * </ul>
 */
public final class FeedItemSelector {

    private FeedItemSelector() {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private static final Pattern SIGN_OFF = Pattern.compile(
            "^(best regards|kind regards|warm regards|with best regards|b\\.?\\s?rgds|brgds|regards|"
                    + "best wishes|thanks (and|&) regards|thanks & best regards|many thanks)[,.!\\s]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTACT_LINE = Pattern.compile(
            "^(t|tel|phone|m|mob|mobile|cell|fax|e|email|e-mail|w|web|website|skype|whatsapp|m/wp|wp)\\s*[:.]\\s*.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://([^/\\s]+)[^\\s)]*");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /** One item as the model will read it. */
    public record Candidate(Long itemId, String sourceName, LocalDateTime when, String title,
                            String text, double score) {

        /** "[source, 14 Sep 2026] title" then the text — the attribution the prompts ask the model to keep. */
        public String block() {
            StringBuilder sb = new StringBuilder("[").append(sourceName);
            if (when != null) sb.append(", ").append(when.format(DAY));
            sb.append(']');
            if (title != null && !title.isBlank()) sb.append(' ').append(title.strip());
            return sb.append('\n').append(text).toString();
        }
    }

    public record Selection(List<Candidate> ranked, int considered, int duplicates, int irrelevant) {
    }

    /**
     * @param terms what counts as relevant; empty means every item is
     */
    public static Selection select(List<FeedItem> items, List<String> terms, LocalDateTime now) {
        List<String> needles = terms.stream().map(t -> t.toLowerCase(Locale.ROOT).strip())
                .filter(t -> !t.isEmpty()).distinct().toList();
        Set<String> hashes = new HashSet<>();
        Set<String> openings = new HashSet<>();
        List<Candidate> kept = new ArrayList<>();
        int duplicates = 0;
        int irrelevant = 0;

        for (FeedItem item : items) {
            String text = clean(item.getText());
            if (text.isBlank()) {
                irrelevant++;
                continue;
            }
            String opening = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
            opening = opening.substring(0, Math.min(200, opening.length()));
            if (!hashes.add(item.getContentHash()) || (opening.length() >= 60 && !openings.add(opening))) {
                duplicates++;
                continue;
            }

            double hits = needles.isEmpty() ? 1
                    : hits(text, needles) + 2.0 * hits(item.getTitle(), needles);
            if (hits == 0) {
                irrelevant++;
                continue;
            }
            LocalDateTime when = item.getPublishedAt() != null ? item.getPublishedAt() : item.getFetchedAt();
            double ageDays = when == null ? 7 : Math.max(0, Duration.between(when, now).toHours() / 24.0);
            double score = hits / (1 + ageDays / 3.0);
            kept.add(new Candidate(item.getId(), item.getSource().getName(), when, item.getTitle(), text, score));
        }

        kept.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Candidate::when, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Selection(kept, items.size(), duplicates, irrelevant);
    }

    /** What an item's keywords are when the topic gives none: the words of its name. */
    public static List<String> terms(String topicName, List<String> keywords) {
        if (keywords != null && !keywords.isEmpty()) return keywords;
        if (topicName == null) return List.of();
        List<String> words = new ArrayList<>();
        for (String w : topicName.split("[^\\p{L}\\p{N}$/]+")) {
            if (w.length() >= 4) words.add(w);
        }
        return words;
    }

    /** A circular with the parts that are not about the market taken out. */
    public static String clean(String raw) {
        String text = FeedText.normalise(raw);
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (String line : lines) {
            if (kept >= 3 && SIGN_OFF.matcher(line).matches()) break; // everything below is the signature
            if (CONTACT_LINE.matcher(line).matches()) continue;
            Matcher url = URL.matcher(line);
            String shortened = url.replaceAll("$1");
            shortened = EMAIL.matcher(shortened).replaceAll("").strip();
            if (shortened.isEmpty() && !line.isBlank()) continue;
            out.append(shortened).append('\n');
            if (!shortened.isBlank()) kept++;
        }
        return FeedText.normalise(out.toString());
    }

    private static int hits(String haystack, List<String> needles) {
        if (haystack == null || haystack.isEmpty()) return 0;
        String h = haystack.toLowerCase(Locale.ROOT);
        int total = 0;
        for (String n : needles) {
            int count = 0;
            for (int i = h.indexOf(n); i >= 0 && count < 5; i = h.indexOf(n, i + n.length())) count++;
            total += count;
        }
        return total;
    }
}
