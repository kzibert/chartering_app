package com.chartering.service.parser;

import com.chartering.model.Company;
import com.chartering.model.Port;
import com.chartering.model.TradeArea;
import com.chartering.model.Vessel;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.PortRepository;
import com.chartering.repository.TradeAreaRepository;
import com.chartering.repository.VesselRepository;
import com.chartering.service.PortDirectory;
import com.chartering.service.TradeAreaGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turning what an email said into rows this database already holds.
 *
 * <p><b>Everything here is exact or nothing, and that is the rule the whole feature rests
 * on.</b> The parser writes to the Cargoes and Open Fleet tabs without being watched, so
 * every lookup it makes has to be one that cannot be wrong in a way nobody notices. A near
 * match is not a small error here: filing a position against the wrong hull puts one owner's
 * ship on another owner's berth, and the row looks exactly like a correct one forever after.
 * Where an exact answer is not available the text is kept and the link left empty — a
 * position that names its port only in words is still a usable position, and Match reads the
 * coarser area instead.
 *
 * <p>The one lookup that is deliberately fuzzy is the trade area, and it is fuzzy in a
 * controlled way: {@link TradeAreaGraph#findIn} matches the longest alias appearing inside a
 * phrase, against a table of spellings a person curated. "SPOT AT MARMARA" resolving to
 * Marmara is not a guess about the world, it is a lookup in a dictionary somebody wrote.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntakeResolver {

    /** How many rows the third tier will look at. A ceiling, not a target. */
    private static final int SUGGESTION_SCAN_LIMIT = 200;

    /** How many make it onto the screen. More than a handful is a search, not a shortlist. */
    private static final int SUGGESTION_LIMIT = 5;

    /** A deadweight no hull has, for switching the size arm of the query off. */
    private static final BigDecimal NO_SIZE = new BigDecimal("-1");

    /**
     * A name pattern no row matches, for switching the name arm off.
     *
     * <p><b>It has to be a string the database will accept, and it was not.</b> This
     * was a NUL character - which reads as "nothing" and looks like a blank in every
     * editor, and which Postgres refuses outright: not "matches no row" but
     * {@code invalid byte sequence for encoding "UTF8": 0x00}, the statement failing and
     * the whole transaction with it. A name arm switched off took the parse down with
     * it, and the emails it happened on could not even record that they had failed.
     *
     * <p>It fired on every vessel named in under four characters, which is not an edge
     * case: TBN is what a circular calls a ship it has not nominated yet, and it is on
     * position lists constantly.
     *
     * <p>No wildcard in it, so LIKE is an equality test against a name no owner has ever
     * given a ship. Legible on sight, unlike its predecessor.
     */
    private static final String NO_NAME = "~~no~such~name~~";

    private final VesselRepository vessels;
    private final PortRepository ports;
    private final CompanyRepository companies;
    private final TradeAreaRepository tradeAreas;
    private final TradeAreaGraph areaGraph;
    private final PortDirectory portDirectory;

    /** How a vessel was identified, because the tab shows it and a reviewer weighs it. */
    public enum VesselMatch {
        /** By IMO. The only identifier that survives a rename; as certain as this gets. */
        IMO,
        /** By her current name, exactly. */
        NAME,
        /** By a former name — the reason {@code vessel_ex_names} exists. */
        EX_NAME,
        /**
         * By an IMO an outside source supplied, which this database already holds.
         *
         * <p>Not something the resolver can produce at parse time — the email gave no usable
         * number, which is why the hull reached the review queue at all. It is reached later,
         * when a web lookup answers with a number and that number turns out to be on a ship
         * here under a name nobody recognised. The IMO is identity, so the identification is
         * as certain as {@link #IMO}; what is less certain is whether the source was talking
         * about this email's ship, and the lookup's own confidence is shown beside it.
         */
        LOOKUP_IMO,
        /** Nothing on file answers to this. */
        NONE
    }

    public record ResolvedVessel(Vessel vessel, VesselMatch how) {
        public boolean found() {
            return vessel != null;
        }
    }

    /**
     * Find the hull a position is about.
     *
     * <p>IMO first, name second, and the order matters more than it looks. A broker's list
     * carries the name she is trading under this week; this database may hold the one she
     * carried in 2019, and {@code vessel_ex_names} is what bridges the two. But a name can
     * be re-used — an owner scraps a ship and gives the name to the next one — while an IMO
     * cannot, so where both are available the number wins.
     *
     * <p>An IMO that matches nothing falls through to the name rather than stopping. The
     * position lists this desk receives mostly carry no IMO at all, and the ones that do are
     * often carrying it for a hull entered here years before anybody was recording them.
     *
     * <p>Two rows sharing an identifier is treated as no match. It is a data fault, and
     * picking whichever came first would hide it behind a plausible-looking position.
     */
    public ResolvedVessel resolveVessel(Extraction.ExtractedVessel parsed) {
        String imo = normaliseImo(parsed.imo());
        if (imo != null) {
            List<Vessel> byImo = vessels.findByImoNumber(imo);
            if (byImo.size() == 1) return new ResolvedVessel(byImo.get(0), VesselMatch.IMO);
            if (byImo.size() > 1) {
                log.warn("IMO {} is on {} vessels; resolving by name instead", imo, byImo.size());
            }
        }

        String name = Extraction.text(parsed.name());
        if (name == null) return new ResolvedVessel(null, VesselMatch.NONE);

        List<Vessel> byName = vessels.findByExactName(name);
        if (byName.size() != 1) {
            if (byName.size() > 1) {
                log.warn("\"{}\" matches {} vessels; treating as unresolved", name, byName.size());
            }
            return new ResolvedVessel(null, VesselMatch.NONE);
        }
        Vessel v = byName.get(0);
        // Which of the two matched, for the reviewer: "matched on a former name" is worth
        // seeing, because it is the case where the answer is right and looks wrong.
        boolean current = v.getName() != null && v.getName().trim().equalsIgnoreCase(name);
        return new ResolvedVessel(v, current ? VesselMatch.NAME : VesselMatch.EX_NAME);
    }

    /**
     * One hull this reading might be about, and why.
     *
     * @param reason the words the review screen prints — "DWT 28,500 against 28,400, built
     *               2003" — because a suggestion a person cannot check is a suggestion they
     *               have to accept on faith
     */
    public record Suggestion(Long vesselId, String name, String imoNumber, String reason) {
    }

    /**
     * The third tier of matching: hulls that look like this one, ranked, for a person to
     * choose from.
     *
     * <p><b>It suggests and never decides, and that division is the point.</b> IMO and name
     * are exact, so a hit on either files the position without asking. Particulars are not:
     * two 28,000-tonners built in 2003 are two ships, and this fleet has plenty of both.
     * Auto-applying on a resemblance would put one owner's position on another owner's hull,
     * in a row that looks exactly like a correct one forever after. So this runs only when
     * the first two tiers found nothing, and what it produces is a shortlist on the
     * {@code NEW_VESSEL} item — one click to link instead of a search, with the evidence
     * printed beside each candidate.
     *
     * <p>Scored on size first, because a deadweight is the one particular a circular almost
     * always carries and the one that is hardest to be coincidentally close on. The build
     * year confirms; the name catches a spelling. Anything scoring nothing is dropped rather
     * than padded out to a fixed five — a screen that always offers suggestions teaches the
     * reader that the suggestions mean nothing.
     */
    public List<Suggestion> suggest(Extraction.ExtractedVessel parsed) {
        BigDecimal dwt = parsed.dwt();
        boolean haveSize = dwt != null && dwt.signum() > 0;
        // An arm the email gave nothing for is switched off with bounds that match nothing,
        // not with a null - the query cannot test a parameter against null. See findSimilar.
        BigDecimal min = NO_SIZE;
        BigDecimal max = NO_SIZE;
        if (haveSize) {
            // Five per cent: wider than the half a percent a field conflict uses, because
            // here the question is "could this be her" rather than "do these agree".
            BigDecimal margin = dwt.multiply(new BigDecimal("0.05"));
            min = dwt.subtract(margin);
            max = dwt.add(margin);
        }

        String name = Extraction.text(parsed.name());
        boolean haveName = name != null && name.length() >= 4;
        // The first four characters, which is enough to keep the query cheap and short
        // enough to survive the letter a broker got wrong.
        String prefix = haveName ? name.substring(0, 4).toLowerCase() + "%" : NO_NAME;
        if (!haveSize && !haveName) return List.of();

        List<Vessel> candidates =
                vessels.findSimilar(min, max, prefix, PageRequest.of(0, SUGGESTION_SCAN_LIMIT));

        record Scored(Suggestion suggestion, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Vessel v : candidates) {
            int score = 0;
            List<String> why = new ArrayList<>();

            // Scored on how close the deadweights are, never on merely having one. The name
            // arm of the query brings in rows of any size, so a hull sharing four letters
            // and nothing else would otherwise collect the size points too — and, worse,
            // print "DWT 3344 against 8181" as a reason to think it is the same ship.
            Double gap = relativeGap(dwt, v.getDeadweightTonnage());
            if (gap != null && gap <= 0.05) {
                score += gap <= 0.02 ? 4 : 3;
                why.add("DWT %s against %s".formatted(
                        plain(v.getDeadweightTonnage()), plain(dwt)));
            }
            if (parsed.built() != null && v.getYearBuilt() != null
                    && Math.abs(parsed.built() - v.getYearBuilt()) <= 1) {
                score += 2;
                why.add("built " + v.getYearBuilt());
            }
            if (name != null && v.getName() != null
                    && v.getName().toLowerCase().startsWith(name.substring(0, Math.min(4, name.length()))
                    .toLowerCase())) {
                score += 2;
                why.add("name starts the same");
            }
            if (score == 0) continue;
            scored.add(new Scored(new Suggestion(v.getId(), v.getName(), v.getImoNumber(),
                    String.join(", ", why)), score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        return scored.stream().limit(SUGGESTION_LIMIT).map(Scored::suggestion).toList();
    }

    /**
     * A port row for a name, or null.
     *
     * <p>Null is the ordinary outcome and not a failure: this database holds the ports this
     * desk works, and a circular names berths all over the world. What the caller does with
     * null — keep the words, resolve the area from them — is the honest reading, and it is
     * the one Match was built to use.
     *
     * <p>Asked of {@link PortDirectory} rather than of the repository, which is what makes it
     * find anything at all in practice. {@code findByExactName} matches the name as the ports
     * table happens to spell it, and a broker writes ILLICHIVSK for a berth renamed in 2016
     * and BOMBAY for one renamed in 1995. The directory reads the aliases too, still exactly
     * and still refusing a name two berths share.
     *
     * <p>The second attempt scans the field for a name among its words — "SALERNO 1/2 SEPT"
     * arrives in this column as often as "SALERNO" does, because a position list writes the
     * berth and the dates in one breath. Words rather than substrings, so it cannot find a
     * berth in Montenegro inside "bar none"; and it is a fallback rather than the first
     * attempt, so a field that is exactly a port name is never read as a phrase containing
     * one.
     *
     * <p>Resolving a berth resolves its water with it, which is the point of doing this at
     * all: {@link #resolveArea} takes the port's own trade area over anything read out of
     * prose, and a placed berth is what lets Match measure the leg in miles rather than in a
     * broker's round days between two seas.
     */
    public Port resolvePort(String text) {
        String name = Extraction.text(text);
        if (name == null) return null;
        return portDirectory.resolve(name)
                // The field read as a whole is a berth this database holds. That covers the
                // spellings as well as the names, which is the whole reason the alias table
                // exists: a circular arriving this week still writes ILLICHIVSK for a port
                // renamed in 2016, and it used to resolve to nothing at all.
                .or(() -> portDirectory.findIn(name))
                .map(PortDirectory.Berth::id)
                .flatMap(ports::findById)
                .orElse(null);
    }

    /**
     * The water a position or a cargo point is on.
     *
     * <p>Three sources in order of authority, and the port's own area beats anything read
     * out of prose. A row in {@code ports} was linked to its area by a person; a phrase was
     * matched against a spelling list. Where the email names a port this application knows,
     * that link is already the answer and reading the sentence again could only disagree
     * with it.
     *
     * @param areaText  what the model put in the area field ("W.MED", "Black Sea")
     * @param portText  what it put in the port field, which often carries the area too
     *                  ("SPOT AT MARMARA" arrives as a port on a bad reading)
     * @param port      the resolved port, when there was one
     */
    public TradeArea resolveArea(String areaText, String portText, Port port) {
        if (port != null && port.getTradeArea() != null) return port.getTradeArea();

        Optional<TradeAreaGraph.Area> area = areaGraph.resolve(areaText);
        if (area.isEmpty()) area = areaGraph.findIn(areaText);
        if (area.isEmpty()) area = areaGraph.findIn(portText);
        // Back to an entity: the graph caches flattened records precisely so a cached row is
        // never a detached entity, so what it hands back cannot be attached to a position.
        return area.map(a -> tradeAreas.findById(a.id()).orElse(null)).orElse(null);
    }

    /**
     * A company by its exact name, or null.
     *
     * <p>Exact or nothing, the same rule the contacts importer follows and for the same
     * reason: two firms a broker keeps apart must not be merged by a machine. Nothing here
     * creates a company — an unrecognised charterer stays as the words the email used, which
     * is what {@code Cargo.notes} carries it in, and is a lead rather than a record.
     */
    public Company resolveCompany(String name) {
        String trimmed = Extraction.text(name);
        if (trimmed == null) return null;
        List<Company> found = companies.findByLowercaseNames(List.of(trimmed.toLowerCase()));
        return found.size() == 1 ? found.get(0) : null;
    }

    /**
     * An ISO date the model produced, or null.
     *
     * <p>Null on anything unparseable rather than an exception. The model is told to write
     * ISO and the schema types the field as a string, so "SPOT" and "end Sept" reach here
     * intact — and they are the correct answer to "when does she open", just not a date one.
     * The words are kept in the matching text column, which is where the model was told to
     * put them and where the screen reads them from.
     */
    public static LocalDate date(String iso) {
        String value = Extraction.text(iso);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Digits only, and seven of them.
     *
     * <p>"IMO 9123456", "9123456" and "imo9123456" are all written; an IMO is seven digits
     * and anything else is a misreading rather than a number to look up. Not checksummed:
     * the check digit would reject a real hull whose number was typed wrong in a circular,
     * and the lookup is exact anyway — a wrong number matches nothing and falls through to
     * the name, which is the outcome a checksum would have forced more expensively.
     */
    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    /** How far apart two figures are as a fraction of the larger, or null if either is absent. */
    private static Double relativeGap(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        BigDecimal larger = a.abs().max(b.abs());
        if (larger.signum() == 0) return 0.0;
        return a.subtract(b).abs()
                .divide(larger, new java.math.MathContext(9, java.math.RoundingMode.HALF_UP))
                .doubleValue();
    }

    /**
     * Public because the web lookup asks the same question of the same string.
     *
     * <p>An email writes "IMO 9133513", "IMO9133513" or "9133513" and they are one number.
     * Two readings of it - one here deciding which hull she is, one there deciding what to
     * search for - would be two chances to disagree about the same seven digits.
     */
    public static String normaliseImo(String raw) {
        String value = Extraction.text(raw);
        if (value == null) return null;
        String digits = value.replaceAll("\\D", "");
        return digits.length() == 7 ? digits : null;
    }
}
