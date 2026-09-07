package com.chartering.service.parser;

import com.chartering.model.Cargo;
import com.chartering.model.Port;
import com.chartering.model.TradeArea;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a freshly read cargo is one already in hand.
 *
 * <p><b>Why this exists at all.</b> A charterer works several brokers, so the same
 * requirement reaches this desk two and three times in a morning, each time phrased
 * differently — "25,000 MT wheat +/- 10%" from one and "abt 25000mt wheat moloo" from
 * another. Left alone that is three cargoes on the Cargoes tab, which is three chances to
 * offer the same ship against what is really one enquiry, and three rows to move to FIXED
 * when it fixes.
 *
 * <p><b>What it does not do is decide.</b> Every candidate it finds becomes a proposal on
 * the Intake tab — merge, or keep separate — because "two firms are working one cargo" and
 * "two firms have similar cargoes" look identical from here and only differ in a fact
 * neither email states. Merging silently would be the one action in this feature that
 * destroys something: two cargoes cannot be un-merged, and the second one's laycan and
 * freight idea are gone.
 *
 * <p><b>Every test is "agree, or one side does not say".</b> A cargo email is mostly silent
 * — the standing example in this codebase names a commodity, a route and a rough size and
 * stops — so treating an absent laycan as a disagreement would find no duplicates at all,
 * and treating it as agreement would find far too many. Absent means the test abstains, and
 * {@link Candidate#reasons()} lists only what actually matched, so a thin candidate reads as
 * thin on the screen rather than arriving with the same confidence as a thorough one.
 */
public final class CargoMatcher {

    private CargoMatcher() {
    }

    /** Quantities this far apart are the same cargo rounded differently. */
    private static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.20");

    /**
     * How far two laycans may sit apart and still be one cargo.
     *
     * <p>Not zero: brokers write a window that is theirs to promise, and the same cargo
     * arrives as "10/15 Sept" from one and "12/18 Sept" from another. Five days either side
     * of a touching overlap covers that without joining a September cargo to an October one.
     */
    private static final long LAYCAN_GRACE_DAYS = 5;

    /**
     * A cargo already on file that this reading might be a second sighting of.
     *
     * @param reasons what actually matched, in the words the review screen prints — "Same
     *                load area (West Med)", "Quantity 25,000 against 25,000". A merge is a
     *                judgement, and a judgement needs the evidence rather than a score.
     */
    public record Candidate(Cargo cargo, List<String> reasons) {
    }

    /**
     * The strongest candidate among the cargoes still worth working, if any.
     *
     * @param candidates every live cargo — the commodity is compared here rather than in SQL,
     *                   because an equality test on it is too brittle against real readings
     * @param loadPort   the incoming cargo's load port, where it resolved to a row
     * @param loadArea   its load area, resolved from the port or from the words
     */
    public static Optional<Candidate> findDuplicate(Extraction.ExtractedCargo parsed,
                                                    List<Cargo> candidates,
                                                    Port loadPort,
                                                    TradeArea loadArea) {
        Candidate best = null;
        for (Cargo existing : candidates) {
            List<String> reasons = agreesWith(parsed, existing, loadPort, loadArea);
            if (reasons == null) continue;
            // Most evidence wins, and the newest wins a tie — the repository returns them
            // id-descending, so the first candidate at a given strength is the most recent.
            if (best == null || reasons.size() > best.reasons().size()) {
                best = new Candidate(existing, reasons);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The reasons these two are one cargo, or null if anything positively says they are not.
     *
     * <p>Null and an empty list are different answers and both are used: null is "these
     * disagree", an empty list would be "nothing matched beyond the commodity" — which is
     * too thin to propose, so it is folded into null by the load-point test being required.
     */
    private static List<String> agreesWith(Extraction.ExtractedCargo parsed, Cargo existing,
                                           Port loadPort, TradeArea loadArea) {
        List<String> reasons = new ArrayList<>();

        String what = commodityAgreement(parsed.commodity(), existing.getCommodity());
        if (what == null) return null;
        reasons.add(what);

        // The load point is the one other test that must actively pass. A commodity alone is
        // far too common - this desk sees wheat every day - and without a route in common
        // there is nothing to distinguish "the same cargo twice" from "the grain trade".
        String where = loadPointAgreement(existing, loadPort, loadArea, parsed);
        if (where == null) return null;
        reasons.add(where);

        if (parsed.quantity() != null && existing.getQuantity() != null) {
            if (!withinTolerance(parsed.quantity(), existing.getQuantity())) return null;
            reasons.add("Quantity %s against %s".formatted(
                    plain(parsed.quantity()), plain(existing.getQuantity())));
        }

        LocalDate from = IntakeResolver.date(parsed.laycanFrom());
        LocalDate to = IntakeResolver.date(parsed.laycanTo());
        if ((from != null || to != null)
                && (existing.getLaycanFrom() != null || existing.getLaycanTo() != null)) {
            if (!laycansOverlap(from, to, existing.getLaycanFrom(), existing.getLaycanTo())) {
                return null;
            }
            reasons.add("Laycans overlap");
        }

        return reasons;
    }

    /**
     * Words a commodity field carries that say nothing about what the cargo is.
     *
     * <p>Every one of these has been seen inside a commodity value rather than beside it —
     * "wheat moloo", "abt 25000mt wheat in bulk" — because a broker writes one line and a
     * reader, human or otherwise, has to decide where the commodity ends. Dropping them is
     * what lets "Wheat" and "wheat moloo" recognise each other; keeping them would make the
     * tolerance a defining feature of the cargo.
     */
    private static final Set<String> NOISE = Set.of(
            "moloo", "molco", "moloc", "abt", "about", "in", "bulk", "cargo", "mt", "mts",
            "tons", "tonnes", "pct", "min", "max", "of", "and", "or", "the");

    /**
     * Are these the same commodity?
     *
     * <p><b>One side's words must all appear in the other's</b> — not merely one word in
     * common. That is the rule real readings forced in both directions. It has to be looser
     * than string equality, because the same enquiry arrives as "Wheat" from one broker and
     * "wheat moloo" from another once a tolerance has been folded into the field, and an
     * equality test never brings the two together. It has to be tighter than a shared word,
     * because "pig iron" and "iron ore" share one and are different cargoes that load
     * differently.
     *
     * <p>Subset rather than intersection is what draws that line: {@code {wheat}} sits inside
     * {@code {wheat, moloo}}, and {@code {pig, iron}} sits inside neither {@code {iron, ore}}
     * nor the reverse. Words throughout, never characters — "iron" as a substring finds both.
     */
    private static String commodityAgreement(String incoming, String existing) {
        Set<String> a = significantWords(incoming);
        Set<String> b = significantWords(existing);
        if (a.isEmpty() || b.isEmpty()) return null;
        if (a.equals(b)) return "Same commodity (" + existing + ")";
        if (b.containsAll(a) || a.containsAll(b)) {
            return "Same commodity, said differently (\"%s\" against \"%s\")"
                    .formatted(incoming.strip(), existing.strip());
        }
        return null;
    }

    /** Lowercased words of two letters or more, with the filler dropped. */
    private static Set<String> significantWords(String commodity) {
        if (commodity == null || commodity.isBlank()) return Set.of();
        Set<String> words = new LinkedHashSet<>();
        for (String token : commodity.toLowerCase().split("[^a-z0-9]+")) {
            if (token.length() < 2 || NOISE.contains(token)) continue;
            // A bare number is a quantity that leaked into the field, never a commodity.
            if (token.chars().allMatch(Character::isDigit)) continue;
            words.add(token);
        }
        return words;
    }

    /**
     * Do they load in the same place?
     *
     * <p>Port before area before words, which is authority order: two rows in {@code ports}
     * are the same berth or they are not, two areas were resolved through a curated alias
     * list, and two strings are whatever two brokers typed. The last of those is compared
     * loosely — "Chornomorsk" and "CHORNOMORSK, UKRAINE" are one place — and it is the
     * weakest evidence in the set, which is why it says so on the screen.
     */
    private static String loadPointAgreement(Cargo existing, Port loadPort, TradeArea loadArea,
                                             Extraction.ExtractedCargo parsed) {
        if (loadPort != null && existing.getLoadPort() != null) {
            return loadPort.getId().equals(existing.getLoadPort().getId())
                    ? "Same load port (" + loadPort.getName() + ")" : null;
        }
        TradeArea existingArea = existing.getLoadArea() != null ? existing.getLoadArea()
                : existing.getLoadPort() != null ? existing.getLoadPort().getTradeArea() : null;
        if (loadArea != null && existingArea != null) {
            return loadArea.getId().equals(existingArea.getId())
                    ? "Same load area (" + loadArea.getName() + ")" : null;
        }
        String incomingText = Extraction.text(parsed.loadPort()) != null
                ? Extraction.text(parsed.loadPort()) : Extraction.text(parsed.loadArea());
        String existingText = existing.getLoadPortText();
        if (incomingText != null && existingText != null && loose(incomingText).equals(loose(existingText))) {
            return "Load point written the same way (" + existingText + ")";
        }
        // One side names a place the other does not. Not a disagreement, but not evidence
        // either, and evidence is what a merge proposal has to be made of.
        return null;
    }

    /** Ranges touch, with the grace above, and a one-sided range counts from its own end. */
    private static boolean laycansOverlap(LocalDate aFrom, LocalDate aTo,
                                          LocalDate bFrom, LocalDate bTo) {
        LocalDate a1 = aFrom != null ? aFrom : aTo;
        LocalDate a2 = aTo != null ? aTo : aFrom;
        LocalDate b1 = bFrom != null ? bFrom : bTo;
        LocalDate b2 = bTo != null ? bTo : bFrom;
        return !a1.minusDays(LAYCAN_GRACE_DAYS).isAfter(b2)
                && !b1.minusDays(LAYCAN_GRACE_DAYS).isAfter(a2);
    }

    private static boolean withinTolerance(BigDecimal a, BigDecimal b) {
        BigDecimal larger = a.abs().max(b.abs());
        if (larger.signum() == 0) return true;
        return a.subtract(b).abs().divide(larger, new MathContext(9, RoundingMode.HALF_UP))
                .compareTo(QUANTITY_TOLERANCE) <= 0;
    }

    private static String loose(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }
}
