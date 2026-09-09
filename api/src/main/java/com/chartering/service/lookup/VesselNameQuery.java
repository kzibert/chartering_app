package com.chartering.service.lookup;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The name to send to a ship database, out of the name a broker wrote.
 *
 * <h2>Why this exists at all</h2>
 * <p><b>The search on the other end is a literal substring match, and nothing in the name a
 * circular carries is guaranteed to be literal.</b> That is not a guess; it is what the
 * source does. Asked for {@code HACI HILMI} it returns the hull held as {@code HACI HILMI-II};
 * asked for {@code HACI HILMI II} — the same name with a space where the hyphen is — it
 * returns nothing at all. {@code MV HACI HILMI} returns nothing. {@code M/V GEISE} returns
 * nothing while {@code GEISE} returns her. A prefix the market puts in front of every ship's
 * name, or a punctuation mark two databases write differently, is the whole difference
 * between finding a hull and being told she does not exist.
 *
 * <p>And it bites in exactly one place: the lookup runs <em>because</em> the IMO is missing,
 * so the name is the only key there is. A name that fails to find her fails the whole
 * feature, silently, and the screen says "nothing came back" — which reads as a fact about
 * the ship rather than about the query.
 *
 * <h2>Two forms, not a search strategy</h2>
 * <p>{@link #forms} returns the query as it should have been sent, and at most one loosening
 * of it. Two, because each is a request against somebody else's server and the second is only
 * worth making when the first found nothing; and only two, because the loosenings past this
 * one stop being the same ship — dropping tokens from {@code SEA STAR} until something
 * answers finds {@code SEA}, and the twenty hulls that come back are noise a person has to
 * read.
 *
 * <p>The loosening is deliberately narrow: a short trailing token is dropped, and only a
 * short one. That is the {@code -II} / {@code II} / {@code 2} suffix, which is where two
 * databases actually disagree about punctuation. A trailing word carrying meaning
 * ({@code STAR}, {@code MARIA}) is left alone, because a substring search without it matches
 * a different ship rather than the same one spelled differently.
 *
 * <h2>The cleaned name is what the matcher scores against too</h2>
 * <p>Not only what is searched for. {@link LookupMatcher} squashes punctuation but knows
 * nothing about {@code MV}, so scoring {@code MV HACI HILMI-II} against the
 * {@code HACI HILMI-II} the search returned would call the name a <em>disagreement</em> — a
 * successful search reported as the wrong ship. One cleaning, used for both.
 */
public final class VesselNameQuery {

    private VesselNameQuery() {
    }

    /**
     * What the market puts in front of a name: MV, M/V, M.V., MT, MS, SS and their kin.
     *
     * <p>Anchored and followed by a separator, so a ship actually called {@code MTS ...} or a
     * name beginning {@code MVK} is untouched. Only a standalone prefix goes.
     */
    private static final Pattern PREFIX = Pattern.compile(
            "^\\s*(?:m[./\\s]?[vts]|s[./\\s]?s|mv|mt|ms|ss|tug|barge)\\b[\\s./-]*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Where a name stops and its history begins.
     *
     * <p>{@code "LOIRE RIVER/ EX AMIKO"} and {@code "ELEMENTS (EX KATERINA)"} are one hull
     * with her past typed into the same field — the shape V11 pulled 299 rows out of. A
     * search for the whole string finds nothing, because no database holds a ship of that
     * name.
     */
    private static final Pattern EX_NAME = Pattern.compile(
            "\\s*(?:[/(\\[]|\\bex[\\s.-])(?:.*)$", Pattern.CASE_INSENSITIVE);

    /** A trailing token short enough to be a hull number rather than part of her name. */
    private static final Pattern SHORT_TAIL = Pattern.compile(
            "^(?<head>.*\\S)[\\s.-]+(?:[ivx]{1,4}|\\d{1,2}|[a-z])$", Pattern.CASE_INSENSITIVE);

    /** What is left of a name once nothing but noise has been taken off it. */
    private static final int MIN_USEFUL_LENGTH = 4;

    /**
     * The name as it should be sent, or the raw one when cleaning would leave nothing.
     *
     * <p>Never returns blank for a non-blank input. A hull genuinely recorded as {@code "MV"}
     * is not a name this can improve, and sending the original at least fails honestly.
     */
    public static String clean(String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.isEmpty()) return null;

        String cleaned = PREFIX.matcher(name).replaceFirst("");
        cleaned = EX_NAME.matcher(cleaned).replaceFirst("");
        // Two spaces where a circular wrapped a line are not a different ship.
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.length() >= 2 ? cleaned : name;
    }

    /**
     * The forms worth asking for, best first, at most two.
     *
     * <p>The caller sends them in order and stops at the first that returns anything. The
     * form that answered is what gets recorded against the lookup, so the drawer's
     * "Searched for …" is the string that was actually sent rather than the one that was
     * meant.
     */
    public static List<String> forms(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) return List.of();

        List<String> out = new ArrayList<>(2);
        out.add(cleaned);

        var tail = SHORT_TAIL.matcher(cleaned);
        if (tail.matches()) {
            String head = tail.group("head").trim();
            // A head shorter than this is not a narrower question, it is a different one.
            if (head.length() >= MIN_USEFUL_LENGTH && !head.equalsIgnoreCase(cleaned)) {
                out.add(head);
            }
        }
        return List.copyOf(out);
    }
}
