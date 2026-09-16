package com.chartering.service.feed;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which figures in a summary do not appear anywhere in the items it was written from.
 *
 * <p>Built after a test run in which the model, asked to summarise two items, wrote a ladder of
 * eight "Danube–Med" rates from $12.50/t to $16.00/t, none of which was in either item. A freight
 * summary with an invented rate is worse than no summary, and the prose gives no sign of it — so
 * every figure is looked up in the material, and the ones that cannot be found are listed under
 * the summary. No model call: it is string matching, and it runs whatever model wrote the text.
 *
 * <p>Deliberately a flag rather than a filter. A figure the model computed honestly ("down 16.5%")
 * or reformatted ("27.5k") will not be found either, and deleting it would be the tool deciding
 * what the broker may read. Listing it is enough to make somebody look.
 *
 * <p>What is not checked: single-digit numbers (bullet counts, "2x30 t cranes" read as a line), four-
 * digit years, and the (source, date) attributions the prompt asks for, whose dates are the model
 * reformatting a header rather than stating a fact.
 */
public final class FigureCheck {

    private FigureCheck() {
    }

    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d])(\\$|US\\$|USD\\s?)?(\\d+(?:[.,]\\d+)*)");
    private static final Pattern THOUSANDS = Pattern.compile("^\\d{1,3}([.,]\\d{3})+$");
    private static final Pattern ATTRIBUTION = Pattern.compile(
            "\\([^()]*(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2} [A-Z][a-z]{2,8} \\d{4})[^()]*\\)");
    private static final Pattern UNIT = Pattern.compile(
            "^\\s?(%|pct|/t|/mt|pmt|/day|pdpr|k\\b|mt\\b|mts\\b|t\\b|dwt|dwcc|cbm|cbft|tons?|tonnes?|days?|nm|usd|bn|billion|million)",
            Pattern.CASE_INSENSITIVE);

    /** The figures, as written with their unit, that no item contains. In order of appearance. */
    public static List<String> unverified(String summary, List<String> material) {
        if (summary == null || summary.isBlank()) return List.of();
        Set<String> known = new HashSet<>();
        for (String m : material) {
            if (m == null) continue;
            Matcher k = NUMBER.matcher(m);
            while (k.find()) known.addAll(readings(k.group(2)));
        }

        String checked = ATTRIBUTION.matcher(summary).replaceAll(" ");
        Set<String> missing = new LinkedHashSet<>();
        Matcher f = NUMBER.matcher(checked);
        while (f.find()) {
            String token = f.group(2);
            List<String> readings = readings(token);
            if (readings.isEmpty() || !worthChecking(token)) continue;
            if (readings.stream().anyMatch(known::contains)) continue;
            Matcher unit = UNIT.matcher(checked.substring(f.end()));
            String currency = f.group(1) == null ? "" : f.group(1).strip();
            missing.add(currency + token + (unit.find() ? unit.group().stripLeading().isEmpty() ? "" : unit.group() : ""));
        }
        return new ArrayList<>(missing);
    }

    private static boolean worthChecking(String token) {
        if (token.matches("\\d")) return false; // a single digit
        if (token.matches("(19|20)\\d{2}")) return false; // a year
        return true;
    }

    /**
     * Every value a token could mean. "27,500" and "27.500" are thousands in a circular; "6.265" is
     * a draught in one line and a deadweight in the next — so an ambiguous token counts as found if
     * either reading is.
     */
    static List<String> readings(String token) {
        List<String> out = new ArrayList<>();
        if (THOUSANDS.matcher(token).matches()) out.add(canonical(token.replaceAll("[.,]", "")));
        String decimal = canonical(token.replace(',', '.'));
        if (decimal != null) out.add(decimal);
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    private static String canonical(String number) {
        try {
            return new BigDecimal(number).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return null; // "1.2.3" — a version, a date; not a figure either reading can match
        }
    }
}
