package com.chartering.service.lookup;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which of the hulls a search returned is the one being asked about.
 *
 * <p><b>The failure to avoid is not "no answer", it is "a plausible wrong answer".</b> A
 * search for a common name returns several ships, and accepting the wrong one writes another
 * vessel's IMO onto this record — after which her whole history is filed under a hull that
 * was never on this desk, and nothing on screen will ever look odd. So the score has to be
 * earned from independent facts, and a candidate that cannot earn it is dropped rather than
 * offered as the best of a bad set.
 *
 * <p><b>Four facts, and the name is the cheapest of them.</b> The name got us here — every
 * candidate matches it to some degree, so it discriminates least. What actually separates two
 * ships of one name is the build year and the deadweight, which are independent of each other
 * and of the name, and which a broker's email usually supplies. The flag corroborates but is
 * weak on its own: ships are reflagged, and this database's flag is often the one she carried
 * when somebody last checked.
 *
 * <p>Every test abstains when either side is silent, exactly as {@code CargoMatcher} does. A
 * position list that gave a name and nothing else can still produce a single unambiguous hit;
 * it just cannot produce a confident one, and the score says so.
 */
public final class LookupMatcher {

    private LookupMatcher() {
    }

    /** What is known about the hull being looked for. Any field may be absent. */
    public record Known(String name, Integer yearBuilt, BigDecimal deadweight, String flag) {
    }

    /**
     * A candidate with its score and the evidence behind it.
     *
     * @param confidence 0-100, the share of the available evidence that agreed
     * @param reasons    what actually matched, in the words the screen prints. A person is
     *                   being asked to accept another database's word for a ship's identity,
     *                   and they can only judge that from the evidence
     */
    public record Scored(VesselParticulars candidate, int confidence, List<String> reasons,
                         List<String> disagreements,
                         /**
                          * Whether anything beyond the name agreed.
                          *
                          * <p>The distinction the score alone cannot make. The name is what
                          * was searched for, so a candidate agreeing on it is not evidence —
                          * it is the query coming back. A hull matched on the name and
                          * nothing else scores 100% and is entirely unverified, and the
                          * screen has to be able to say so.
                          */
                         boolean corroborated) {
    }

    /** Deadweights this close are the same ship rounded differently by two databases. */
    private static final BigDecimal DWT_TOLERANCE = new BigDecimal("0.05");

    /**
     * The best candidate, or empty when none of them earns the floor.
     *
     * <p>Ties are not broken arbitrarily: two candidates scoring the same is precisely the
     * situation where a machine should not choose, so the result is empty and the reviewer
     * sees the whole list instead.
     */
    public static Optional<Scored> best(Known known, List<VesselParticulars> candidates,
                                        int minConfidence) {
        List<Scored> scored = score(known, candidates);
        if (scored.isEmpty()) return Optional.empty();

        Scored top = scored.get(0);
        if (top.confidence() < minConfidence) return Optional.empty();
        if (scored.size() > 1 && scored.get(1).confidence() == top.confidence()) {
            // Two ships answering equally well is not a match, it is a question.
            return Optional.empty();
        }
        // Nothing but the name agreed, and more than one ship answers to it. That is the
        // shape of the mistake this whole class exists to avoid: the name is the query, so
        // agreeing on it proves nothing, and with several candidates there is no reason to
        // prefer this one. A single unambiguous hit is still offered - flagged uncorroborated
        // so the screen can say what it rests on.
        if (!top.corroborated() && candidates.size() > 1) return Optional.empty();
        return Optional.of(top);
    }

    /** Every candidate scored, best first — what the review screen lists. */
    public static List<Scored> score(Known known, List<VesselParticulars> candidates) {
        List<Scored> out = new ArrayList<>();
        for (VesselParticulars c : candidates) {
            out.add(scoreOne(known, c));
        }
        out.sort(Comparator.comparingInt(Scored::confidence).reversed());
        return out;
    }

    private static Scored scoreOne(Known known, VesselParticulars c) {
        int earned = 0;
        int available = 0;
        boolean corroborated = false;
        List<String> reasons = new ArrayList<>();
        List<String> disagreements = new ArrayList<>();

        // ---- the name (weight 2) ----
        // Lowest weight of the three real tests, because it is the thing that was searched
        // for: every candidate resembles it, so agreement here says least.
        if (known.name() != null && c.name() != null) {
            available += 2;
            String a = squash(known.name());
            String b = squash(c.name());
            if (a.equals(b)) {
                earned += 2;
                reasons.add("Name matches exactly");
            } else if (b.startsWith(a) || a.startsWith(b)) {
                // "HACI HILMI" against "HACI HILMI-II" - the punctuation and the suffix are
                // how one database writes what another writes differently.
                earned += 1;
                reasons.add("Name is close (\"%s\" against \"%s\")".formatted(c.name(), known.name()));
            } else {
                disagreements.add("Name differs (\"%s\" against \"%s\")"
                        .formatted(c.name(), known.name()));
            }
        }

        // ---- the build year (weight 3) ----
        // Independent of the name and cheap to be sure about: two ships of one name built in
        // the same year is rare enough to be worth leaning on.
        if (known.yearBuilt() != null && c.yearBuilt() != null) {
            available += 3;
            int gap = Math.abs(known.yearBuilt() - c.yearBuilt());
            if (gap == 0) {
                earned += 3;
                corroborated = true;
                reasons.add("Built " + c.yearBuilt());
            } else if (gap == 1) {
                // A year either way: keel laid in one and delivered in the next, which two
                // databases record differently.
                earned += 2;
                corroborated = true;
                reasons.add("Built %d against %d".formatted(c.yearBuilt(), known.yearBuilt()));
            } else {
                disagreements.add("Built %d, not %d".formatted(c.yearBuilt(), known.yearBuilt()));
            }
        }

        // ---- the deadweight (weight 3) ----
        if (known.deadweight() != null && c.deadweightTonnage() != null) {
            available += 3;
            Double gap = relativeGap(known.deadweight(), c.deadweightTonnage());
            if (gap != null && gap <= DWT_TOLERANCE.doubleValue()) {
                earned += 3;
                corroborated = true;
                reasons.add("DWT %s against %s".formatted(
                        plain(c.deadweightTonnage()), plain(known.deadweight())));
            } else {
                disagreements.add("DWT %s, not %s".formatted(
                        plain(c.deadweightTonnage()), plain(known.deadweight())));
            }
        }

        // ---- the flag (weight 1) ----
        // Weakest on purpose. Ships are reflagged and this database's flag is often the one
        // she wore when somebody last looked, so a disagreement here is nearly meaningless -
        // it is recorded as evidence for the reader and costs almost nothing.
        if (known.flag() != null && c.flag() != null) {
            available += 1;
            if (squash(known.flag()).equals(squash(c.flag()))) {
                earned += 1;
                // The flag corroborates only weakly, but it is independent of the name,
                // which is the property that matters here.
                corroborated = true;
                reasons.add("Flag " + c.flag());
            } else {
                disagreements.add("Flies %s, on file as %s".formatted(c.flag(), known.flag()));
            }
        }

        // Nothing to go on at all. Zero rather than a false hundred: an unverifiable
        // candidate is the one most in need of being marked unverifiable.
        int confidence = available == 0 ? 0 : Math.round((earned * 100f) / available);
        return new Scored(c, confidence, List.copyOf(reasons), List.copyOf(disagreements),
                corroborated);
    }

    private static Double relativeGap(BigDecimal a, BigDecimal b) {
        BigDecimal larger = a.abs().max(b.abs());
        if (larger.signum() == 0) return 0.0;
        return a.subtract(b).abs()
                .divide(larger, new MathContext(9, RoundingMode.HALF_UP)).doubleValue();
    }

    /** Case, spacing and punctuation dropped: "HACI HILMI-II" is "hacihilmiii". */
    private static String squash(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }
}
