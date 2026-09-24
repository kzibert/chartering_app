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
import java.util.Objects;
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
 * <p><b>Every test is an anchor that agrees, abstains or objects.</b> A cargo email is mostly
 * silent — the standing example in this codebase names a commodity, a route and a rough size
 * and stops — so treating an absent laycan as a disagreement would find no duplicates at all,
 * and treating it as agreement would find far too many. Absent means the anchor abstains; any
 * objection rules the pair out; {@link Candidate#reasons()} lists only what actually agreed, so
 * a thin candidate reads as thin on the screen rather than arriving with the same confidence as
 * a thorough one. The anchors, in order:
 *
 * <ol>
 *   <li>commodity — required, compared on words (see {@link #commodityAgreement});</li>
 *   <li>load point — required, port before area before words;</li>
 *   <li>discharge point — may abstain, may object. Before it was an anchor, "wheat
 *       Chornomorsk to Spain" and "wheat Chornomorsk to Egypt" were proposed as one cargo,
 *       because nothing compared where either was going;</li>
 *   <li>quantity — within 20% or it objects; within {@value #TIGHT_PERCENT}% it identifies;</li>
 *   <li>laycan — overlapping, with a grace, or it objects;</li>
 *   <li>charterer — where both name one on file, different firms object;</li>
 *   <li>sender — whether the firm sending this has already told us about that cargo.</li>
 * </ol>
 *
 * <p><b>Two strengths, because the queue showed there had to be two.</b> Cargo 122 was asked
 * about seven times, six of them by a broker already on it as a source: he was re-sending his
 * own enquiry, and "one cargo or two similar ones?" has no second answer when one firm says the
 * same thing twice. So a candidate is {@link Strength#CERTAIN} — merged on arrival, gap-fill
 * only, with the source row saying it was automatic — where either
 *
 * <ul>
 *   <li>the sender is already a source of that cargo and two of the three identifying anchors
 *       agree (quantity within {@value #TIGHT_PERCENT}%, the discharge point, the laycan); or</li>
 *   <li>all three identifying anchors agree <em>and</em> the load point agrees at port level —
 *       the same berth, not only the same water. That is one enquiry however many brokers are
 *       working it.</li>
 * </ul>
 *
 * <p><b>Everything else that survives is {@link Strength#PROBABLE}, and is not decided
 * here.</b> It becomes a proposal on the Intake tab — merge, or keep separate — because "two
 * firms are working one cargo" and "two firms have similar cargoes" look identical from here
 * and only differ in a fact neither email states. Merging those silently would be the one
 * action in this feature that destroys something: the second one's laycan and freight idea are
 * gone. A certain merge destroys nothing of the kind, because it only gap-fills and the arrival
 * stays on the cargo as a source, readable in full.
 */
public final class CargoMatcher {

    private CargoMatcher() {
    }

    /** Quantities this far apart are the same cargo rounded differently. */
    private static final BigDecimal QUANTITY_TOLERANCE = new BigDecimal("0.20");

    /**
     * Quantities this close are the same figure, not merely the same size of parcel.
     *
     * <p>Five per cent is a broker rounding 24,750 up to 25,000 or folding a tolerance into
     * the figure; twenty is the next enquiry of the same kind. Only the tight one counts toward
     * a merge nobody is asked about.
     */
    static final int TIGHT_PERCENT = 5;
    private static final BigDecimal TIGHT_TOLERANCE = new BigDecimal("0.05");

    /**
     * How far two laycans may sit apart and still be one cargo.
     *
     * <p>Not zero: brokers write a window that is theirs to promise, and the same cargo
     * arrives as "10/15 Sept" from one and "12/18 Sept" from another. Five days either side
     * of a touching overlap covers that without joining a September cargo to an October one.
     */
    private static final long LAYCAN_GRACE_DAYS = 5;

    /** How sure a candidate is — see the class comment for what earns each. */
    public enum Strength {
        /** Looks like one in hand; a person decides. */
        PROBABLE,
        /** Is one in hand; merged on arrival, gap-fill only. */
        CERTAIN
    }

    /**
     * A cargo already on file that this reading might be a second sighting of.
     *
     * @param reasons  what actually matched, in the words the review screen prints — "Same
     *                 load area (West Med)", "Quantity 25,000 against 25,000". A merge is a
     *                 judgement, and a judgement needs the evidence rather than a score.
     * @param strength whether the anchors settle it, or a person has to
     */
    public record Candidate(Cargo cargo, List<String> reasons, Strength strength) {

        public Candidate(Cargo cargo, List<String> reasons) {
            this(cargo, reasons, Strength.PROBABLE);
        }

        public boolean certain() {
            return strength == Strength.CERTAIN;
        }
    }

    /** What the anchors said about one pair, before it is ranked. */
    private record Evidence(List<String> reasons, boolean loadAtPort, boolean dischargeAgrees,
                            boolean quantityTight, boolean laycanOverlaps, boolean sameSender) {

        Strength strength() {
            int identifying = (quantityTight ? 1 : 0) + (dischargeAgrees ? 1 : 0)
                    + (laycanOverlaps ? 1 : 0);
            if (sameSender && identifying >= 2) return Strength.CERTAIN;
            if (loadAtPort && identifying == 3) return Strength.CERTAIN;
            return Strength.PROBABLE;
        }
    }

    /**
     * The strongest candidate among the cargoes still worth working, if any.
     *
     * <p>The shape the paste screen uses: a load point and nothing else resolved. The
     * discharge point and charterer abstain, and nobody is a known sender, so nothing it finds
     * is ever certain — which is right there, since a paste writes nothing on its own anyway.
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
        return findDuplicate(new ResolvedCargo(parsed, loadPort, loadArea, null, null,
                        IntakeResolver.date(parsed.laycanFrom()),
                        IntakeResolver.date(parsed.laycanTo()), null),
                candidates, Set.of());
    }

    /**
     * The strongest candidate, weighed on every anchor this reading resolved.
     *
     * @param sourcedBySender the cargoes the firm sending this one has already told us about —
     *                        the anchor that turns a re-sent enquiry from a question into a
     *                        certainty
     */
    public static Optional<Candidate> findDuplicate(ResolvedCargo resolved,
                                                    List<Cargo> candidates,
                                                    Set<Long> sourcedBySender) {
        Candidate best = null;
        boolean bestSameSender = false;
        for (Cargo existing : candidates) {
            boolean sameSender = existing.getId() != null && sourcedBySender.contains(existing.getId());
            Evidence evidence = agreesWith(resolved, existing, sameSender);
            if (evidence == null) continue;
            Candidate candidate = new Candidate(existing, evidence.reasons(), evidence.strength());
            // Certain beats probable; the sender's own cargo beats somebody else's, because
            // that is the one he is most likely re-sending; then most evidence. The newest wins
            // a tie - the repository returns them id-descending, so the first seen at a given
            // rank is the most recent.
            if (best == null || outranks(candidate, sameSender, best, bestSameSender)) {
                best = candidate;
                bestSameSender = sameSender;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean outranks(Candidate a, boolean aSender, Candidate b, boolean bSender) {
        if (a.strength() != b.strength()) return a.strength().compareTo(b.strength()) > 0;
        if (aSender != bSender) return aSender;
        return a.reasons().size() > b.reasons().size();
    }

    /**
     * The evidence these two are one cargo, or null if any anchor says they are not.
     *
     * <p>Null and thin evidence are different answers and both are used: null is "these
     * disagree", while nothing beyond the commodity would be too thin to propose — which is
     * folded into null by the load-point anchor being required.
     */
    private static Evidence agreesWith(ResolvedCargo resolved, Cargo existing, boolean sameSender) {
        Extraction.ExtractedCargo parsed = resolved.parsed();
        List<String> reasons = new ArrayList<>();

        String what = commodityAgreement(parsed.commodity(), existing.getCommodity());
        if (what == null) return null;
        reasons.add(what);

        // The load point is the one other anchor that must actively agree. A commodity alone is
        // far too common - this desk sees wheat every day - and without a route in common
        // there is nothing to distinguish "the same cargo twice" from "the grain trade".
        Place load = placeAgreement("load", existing.getLoadPort(), existing.getLoadArea(),
                existing.getLoadPortText(), resolved.loadPort(), resolved.loadArea(),
                firstText(parsed.loadPort(), parsed.loadArea()));
        if (load.verdict() != Verdict.AGREE) return null;
        reasons.add(load.reason());

        Place discharge = placeAgreement("discharge", existing.getDischargePort(),
                existing.getDischargeArea(), existing.getDischargePortText(),
                resolved.dischargePort(), resolved.dischargeArea(),
                firstText(parsed.dischargePort(), parsed.dischargeArea()));
        if (discharge.verdict() == Verdict.DISAGREE) return null;
        if (discharge.verdict() == Verdict.AGREE) reasons.add(discharge.reason());

        boolean quantityTight = false;
        if (parsed.quantity() != null && existing.getQuantity() != null) {
            if (!withinTolerance(parsed.quantity(), existing.getQuantity(), QUANTITY_TOLERANCE)) {
                return null;
            }
            quantityTight = withinTolerance(parsed.quantity(), existing.getQuantity(), TIGHT_TOLERANCE);
            reasons.add("Quantity %s against %s".formatted(
                    plain(parsed.quantity()), plain(existing.getQuantity())));
        }

        boolean laycanOverlaps = false;
        LocalDate from = resolved.laycanFrom();
        LocalDate to = resolved.laycanTo();
        if ((from != null || to != null)
                && (existing.getLaycanFrom() != null || existing.getLaycanTo() != null)) {
            if (!laycansOverlap(from, to, existing.getLaycanFrom(), existing.getLaycanTo())) {
                return null;
            }
            laycanOverlaps = true;
            reasons.add("Laycans overlap");
        }

        // A charterer named on both sides is identity: two different firms' wheat is two
        // cargoes whatever else agrees. Named on one side only, it abstains like the rest.
        if (resolved.charterer() != null && existing.getChartererCompany() != null) {
            if (!Objects.equals(resolved.charterer().getId(), existing.getChartererCompany().getId())) {
                return null;
            }
            reasons.add("Same charterer (" + existing.getChartererCompany().getName() + ")");
        }

        if (sameSender) reasons.add("This sender has sent it before");

        return new Evidence(reasons, load.portLevel(), discharge.verdict() == Verdict.AGREE,
                quantityTight, laycanOverlaps, sameSender);
    }

    private static String firstText(String a, String b) {
        return Extraction.text(a) != null ? Extraction.text(a) : Extraction.text(b);
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

    private enum Verdict { AGREE, DISAGREE, ABSTAIN }

    /** One place anchor's answer, with the words for the screen when it agreed. */
    private record Place(Verdict verdict, String reason, boolean portLevel) {
        static final Place ABSTAIN = new Place(Verdict.ABSTAIN, null, false);
        static final Place DISAGREE = new Place(Verdict.DISAGREE, null, false);
    }

    /**
     * Do they load — or discharge — in the same place?
     *
     * <p>Port before area before words, which is authority order: two rows in {@code ports}
     * are the same berth or they are not, two areas were resolved through a curated alias
     * list, and two strings are whatever two brokers typed. The last of those is compared
     * loosely — "Chornomorsk" and "CHORNOMORSK, UKRAINE" are one place — and it is the
     * weakest evidence in the set, which is why it says so on the screen. Only a port-level
     * agreement counts toward a certain match: a sea in common is a route, not a berth.
     *
     * <p>An area is taken from the port where only the port resolved, on either side, so a
     * cargo filed against Constanza and a reading that says only "Black Sea" meet at the area
     * rather than abstaining.
     */
    private static Place placeAgreement(String which, Port existingPort, TradeArea existingArea,
                                        String existingText, Port port, TradeArea area,
                                        String text) {
        if (port != null && existingPort != null) {
            return port.getId().equals(existingPort.getId())
                    ? new Place(Verdict.AGREE, "Same " + which + " port (" + port.getName() + ")", true)
                    : Place.DISAGREE;
        }
        TradeArea onFile = existingArea != null ? existingArea
                : existingPort != null ? existingPort.getTradeArea() : null;
        TradeArea incoming = area != null ? area : port != null ? port.getTradeArea() : null;
        if (incoming != null && onFile != null) {
            return incoming.getId().equals(onFile.getId())
                    ? new Place(Verdict.AGREE, "Same " + which + " area (" + incoming.getName() + ")", false)
                    : Place.DISAGREE;
        }
        if (text != null && existingText != null && loose(text).equals(loose(existingText))) {
            return new Place(Verdict.AGREE, Character.toUpperCase(which.charAt(0))
                    + which.substring(1) + " point written the same way (" + existingText + ")", false);
        }
        // One side names a place the other does not. Not a disagreement, but not evidence
        // either, and evidence is what a merge proposal has to be made of.
        return Place.ABSTAIN;
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

    private static boolean withinTolerance(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        BigDecimal larger = a.abs().max(b.abs());
        if (larger.signum() == 0) return true;
        return a.subtract(b).abs().divide(larger, new MathContext(9, RoundingMode.HALF_UP))
                .compareTo(tolerance) <= 0;
    }

    private static String loose(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }
}
