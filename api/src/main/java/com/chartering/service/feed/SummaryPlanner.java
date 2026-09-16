package com.chartering.service.feed;

import com.chartering.model.FeedSummaryStrategy;
import com.chartering.service.feed.FeedItemSelector.Candidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a topic's ranked items are made to fit the model's context window.
 *
 * <h2>The budget</h2>
 * <p>A request is the system prompt, the material and the answer, and all three share the
 * window. So the room for material is the window less the rendered prompt, less the answer the
 * stage reserves, less a margin — five percent, for the chat template's own tokens and for
 * counting a joined text as the sum of its parts. Computed separately for the notes stage and the
 * summary stage, because their prompts and their answers differ.
 *
 * <h2>The approaches, in order of preference</h2>
 * <ul>
 *   <li><b>Single pass</b> when everything fits one request. Always preferred: every intermediate
 *       step is a place for a figure to be dropped or rounded.
 *   <li><b>Map-reduce</b> otherwise. Items are packed into batches that each fit the notes
 *       budget — grouped by source, so a batch's notes can say who said what, and in rank order,
 *       so the best material is read first. An item larger than a batch is cut on paragraph
 *       boundaries, then lines. Each batch becomes notes; if the notes together still exceed the
 *       summary budget they are batched and condensed again, level by level, before the summary
 *       is written from them. The run does the reduce levels, because how long the notes come
 *       out is only known once they have.
 *   <li><b>The call cap</b> bounds GPU time per topic. When the estimated calls exceed it, the
 *       lowest-ranked items are dropped until they do not — or, where a single pass over the best
 *       items would read more of them than a capped map-reduce could, that is what runs. The
 *       number dropped is part of the result, and it reaches the screen.
 * </ul>
 */
public final class SummaryPlanner {

    private SummaryPlanner() {
    }

    static final String SEPARATOR = "\n\n---\n\n";
    private static final double MARGIN = 0.05;

    public record Budget(int contextWindow, int summaryPromptTokens, int notesPromptTokens,
                         int summaryMaxTokens, int notesMaxTokens, int maxCalls) {

        public int forSummary() {
            return room(summaryPromptTokens, summaryMaxTokens);
        }

        public int forNotes() {
            return room(notesPromptTokens, notesMaxTokens);
        }

        private int room(int prompt, int answer) {
            int margin = (int) Math.ceil(contextWindow * MARGIN) + 32;
            return Math.max(0, contextWindow - prompt - answer - margin);
        }
    }

    /**
     * @param batches        map-reduce only: the texts of the notes calls, each within the notes budget
     * @param estimatedCalls notes calls, estimated reduce calls, and the summary call
     */
    public record Plan(FeedSummaryStrategy strategy, List<Candidate> used, int dropped,
                       List<String> singlePassMaterial, List<String> batches, int estimatedCalls) {
    }

    public static Plan plan(List<Candidate> ranked, Budget budget, TokenCounter counter) {
        if (budget.forSummary() <= 0 || budget.forNotes() <= 0) {
            throw new IllegalArgumentException("The prompt and the answer leave no room for material in a "
                    + budget.contextWindow() + "-token window. Shorten the prompt or lower the answer sizes.");
        }
        if (ranked.isEmpty()) {
            return new Plan(FeedSummaryStrategy.SINGLE_PASS, List.of(), 0, List.of(), List.of(), 0);
        }

        Map<Candidate, Integer> tokens = new HashMap<>();
        for (Candidate c : ranked) tokens.put(c, counter.count(c.block()));
        int separatorTokens = counter.count(SEPARATOR);

        // 1. Everything in one request.
        int singleCount = longestPrefixFitting(ranked, tokens, separatorTokens, budget.forSummary());
        if (singleCount == ranked.size()) {
            return single(ranked, ranked.size());
        }

        // 2. Map-reduce, dropping from the bottom until the estimate is under the cap.
        if (budget.maxCalls() >= 2) {
            int keep = ranked.size();
            while (keep > 0) {
                List<Candidate> used = ranked.subList(0, keep);
                List<String> batches = pack(groupedBySource(used), budget.forNotes(), counter, tokens);
                int calls = estimateCalls(batches.size(), budget);
                if (calls <= budget.maxCalls()) {
                    // 3. ...unless the best items in one pass would read more of them.
                    if (keep > singleCount) {
                        return new Plan(FeedSummaryStrategy.MAP_REDUCE, List.copyOf(used), ranked.size() - keep,
                                List.of(), batches, calls);
                    }
                    break;
                }
                keep--;
            }
        }
        return single(ranked, Math.max(singleCount, 0));
    }

    /**
     * How many calls a map-reduce of this many batches is expected to take: the notes calls, the
     * condensing levels needed if every batch's notes came back at full length, and the summary.
     * Pessimistic on purpose — notes are usually shorter — so the cap is honoured, not hoped for.
     */
    static int estimateCalls(int batches, Budget budget) {
        int calls = batches;
        int noteTokens = batches * budget.notesMaxTokens();
        int perGroup = Math.max(1, budget.forNotes() / Math.max(1, budget.notesMaxTokens()));
        int groups = batches;
        while (noteTokens > budget.forSummary() && groups > 1) {
            groups = (int) Math.ceil(groups / (double) perGroup);
            calls += groups;
            noteTokens = groups * budget.notesMaxTokens();
        }
        return calls + 1;
    }

    /**
     * Texts packed into as few groups as fit {@code available} tokens each, in order, with any
     * text too large for a group of its own cut into parts that are not.
     */
    public static List<String> pack(List<String> texts, int available, TokenCounter counter) {
        Map<String, Integer> cache = new HashMap<>();
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int used = 0;
        int sep = counter.count(SEPARATOR);
        for (String text : texts) {
            for (String part : splitToFit(text, available, counter)) {
                int t = cache.computeIfAbsent(part, counter::count);
                int cost = current.isEmpty() ? t : t + sep;
                if (!current.isEmpty() && used + cost > available) {
                    groups.add(current);
                    current = new ArrayList<>();
                    used = 0;
                    cost = t;
                }
                current.add(part);
                used += cost;
            }
        }
        if (!current.isEmpty()) groups.add(current);
        return groups.stream().map(g -> String.join(SEPARATOR, g)).toList();
    }

    private static List<String> pack(List<Candidate> candidates, int available, TokenCounter counter,
                                     Map<Candidate, Integer> tokens) {
        return pack(candidates.stream().map(Candidate::block).toList(), available, counter);
    }

    /** A text as parts that each fit: paragraphs, then lines, then a hard cut. */
    static List<String> splitToFit(String text, int available, TokenCounter counter) {
        if (counter.count(text) <= available) return List.of(text);
        List<String> parts = new ArrayList<>();
        String[] paragraphs = text.split("\n\n");
        String unit = "\n\n";
        if (paragraphs.length == 1) {
            paragraphs = text.split("\n");
            unit = "\n";
        }
        if (paragraphs.length == 1) {
            return hardCut(text, available, counter);
        }
        StringBuilder current = new StringBuilder();
        for (String p : paragraphs) {
            String candidate = current.isEmpty() ? p : current + unit + p;
            if (counter.count(candidate) <= available) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) parts.add(current.toString());
            current.setLength(0);
            if (counter.count(p) <= available) {
                current.append(p);
            } else {
                parts.addAll(splitToFit(p, available, counter));
            }
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    private static List<String> hardCut(String text, int available, TokenCounter counter) {
        List<String> parts = new ArrayList<>();
        int total = Math.max(1, counter.count(text));
        int chunk = Math.max(1, (int) (text.length() * (available / (double) total) * 0.9));
        for (int i = 0; i < text.length(); i += chunk) {
            parts.add(text.substring(i, Math.min(text.length(), i + chunk)));
        }
        return parts;
    }

    /** Ranked order kept inside each source, and sources ordered by their best item. */
    private static List<Candidate> groupedBySource(List<Candidate> ranked) {
        Map<String, List<Candidate>> bySource = new LinkedHashMap<>();
        for (Candidate c : ranked) bySource.computeIfAbsent(c.sourceName(), k -> new ArrayList<>()).add(c);
        return bySource.values().stream().flatMap(List::stream).toList();
    }

    private static int longestPrefixFitting(List<Candidate> ranked, Map<Candidate, Integer> tokens,
                                            int separatorTokens, int available) {
        int used = 0;
        for (int i = 0; i < ranked.size(); i++) {
            int cost = tokens.get(ranked.get(i)) + (i == 0 ? 0 : separatorTokens);
            if (used + cost > available) return i;
            used += cost;
        }
        return ranked.size();
    }

    private static Plan single(List<Candidate> ranked, int count) {
        List<Candidate> used = List.copyOf(ranked.subList(0, count));
        return new Plan(FeedSummaryStrategy.SINGLE_PASS, used, ranked.size() - count,
                used.stream().map(Candidate::block).toList(), List.of(), count == 0 ? 0 : 1);
    }
}
