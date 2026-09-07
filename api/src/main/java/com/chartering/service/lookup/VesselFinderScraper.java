package com.chartering.service.lookup;

import com.chartering.config.VesselLookupProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Particulars read off a public ship-database search page.
 *
 * <h2>What this is and what it costs</h2>
 * <p>It fetches one search page per lookup and reads the result rows. That is enough for the
 * job — the row carries the IMO in its link, and the name, type, flag, build year, gross
 * tonnage, deadweight and dimensions in its cells — so a hull can be both identified and
 * described from a single request.
 *
 * <p><b>Three things are true about it and none of them should be discovered later.</b> The
 * site's terms do not invite automated reading. The parse depends on class names that belong
 * to somebody else's stylesheet and can change on any day without notice. And the traffic
 * lands on a server this desk does not pay for. All three were weighed against paid APIs at
 * £100–£700 a month and the trade was made deliberately; what follows from it is the care
 * taken elsewhere — one request at a time, a gap between them, a cap per pass, and nothing
 * requested unless somebody is actually waiting on a review item.
 *
 * <p>It is written against {@link VesselLookupProvider} so the trade stays reversible. When a
 * key is bought, this class is replaced rather than unpicked.
 *
 * <h2>What is deliberately not read</h2>
 * <p>The detail page carries a draught figure and it is not taken. That number is the
 * AIS-reported <em>current</em> draught — how deep she floats today, loaded — and the column
 * it would land in is a design maximum. A loaded reading in a design field is wrong in the
 * direction that quietly loses cargoes, and it would look entirely plausible. Gear, holds,
 * capacities and fittings are not offered either: the source does not carry them in a form
 * worth trusting, and a field that is sometimes right is worse here than one that is absent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VesselFinderScraper implements VesselLookupProvider {

    /**
     * The IMO lives in the row's own link: {@code /vessels/details/9014561}.
     *
     * <p><b>The trailing guard is load-bearing, and its absence produced invented IMO
     * numbers.</b> Not every row on a search page is a ship with an IMO - pleasure craft,
     * sailing yachts and small workboats are listed by MMSI, and the link is then
     * {@code /vessels/details/224066450}, nine digits. Without the guard the pattern took
     * the first seven of them and reported 2240664 as an IMO: a number belonging to no
     * vessel anywhere, on a candidate somebody could have accepted onto a hull. A search
     * for TARANTO returned six rows and three carried a fabricated number this way - and
     * two of those three, being named TARANTO exactly, tied with the real ship and had the
     * whole lookup refused as ambiguous.
     *
     * <p>Seven digits and then something that is not one. A row that cannot supply a real
     * number is dropped, which is what {@code readRow} does with a null.
     */
    private static final Pattern DETAILS_IMO =
            Pattern.compile("/vessels/details/(\\d{7})(?!\\d)");

    /** "112 / 15" — length and beam in metres, in one cell. */
    private static final Pattern LENGTH_BEAM =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)");

    /**
     * Every row on the page, not the handful that are kept.
     *
     * <p><b>Ranking has to happen after reading, not during it.</b> The page is ordered by
     * the source's own idea of relevance, which knows only the name it was asked for — a
     * search for a common name returns twenty hulls and the one whose deadweight and build
     * year actually agree can be the twelfth. Cutting the list here at
     * {@code max-candidates} threw her away before anything had looked at her, and the
     * lookup then recorded NO_MATCH for a ship whose row was on the page it read. The cap
     * still applies: {@link VesselLookupService} keeps the best few once they are scored.
     *
     * <p>What remains here is a guard against a page that is not what it should be. One
     * search page carries a few dozen rows at most; a document offering thousands is a
     * redirect, a rebuild or a listing, and reading all of it would be a waste rather than
     * an answer.
     */
    private static final int PAGE_LIMIT = 50;

    private final VesselLookupProperties props;

    @Override
    public String name() {
        return "vesselfinder";
    }

    @Override
    public List<VesselParticulars> searchByName(String name) {
        String url = props.getSearchUrl()
                .replace("{name}", URLEncoder.encode(name.trim(), StandardCharsets.UTF_8));

        Document doc;
        try {
            doc = Jsoup.connect(url)
                    .userAgent(props.getUserAgent())
                    .timeout(props.getReadTimeoutMs())
                    // Follow redirects but never a form or a script: this reads one page.
                    .followRedirects(true)
                    .get();
        } catch (IOException e) {
            // The common failures are a block page and a timeout, and both are facts about
            // the source rather than about the ship — the caller records them as such and
            // does not retry in a loop.
            throw new LookupException(
                    "Could not read " + url + " — " + e.getMessage(), e);
        }

        List<VesselParticulars> out = parse(doc);

        if (out.isEmpty() && doc.select("a.ship-link").isEmpty()) {
            // No rows at all is ambiguous: either she is genuinely not in the database, or
            // the page has been rebuilt and this parser is now reading nothing off every
            // search. The second is silent and would look exactly like the first, so it is
            // worth a log line that says which page produced nothing.
            log.info("Vessel lookup found no result rows at {} — either no such ship, or the "
                    + "page structure has changed", url);
        }
        return out;
    }

    /**
     * The result rows of a search page, as particulars.
     *
     * <p>Split from the fetch so the parse can be tested against a saved page without
     * touching the network — which is the half worth testing, and the half that breaks. A
     * test that made the real request would be flaky, slow, and rude to a server this project
     * has no arrangement with.
     */
    List<VesselParticulars> parse(Document doc) {
        List<VesselParticulars> out = new ArrayList<>();
        // One row per hull. Selected by the link class rather than by table position, so a
        // banner or an advertising row inserted above the table does not shift everything.
        for (Element link : doc.select("a.ship-link")) {
            if (out.size() >= PAGE_LIMIT) break;
            VesselParticulars p = readRow(link);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * One result row.
     *
     * <p>Null when the row carries no IMO. Without one it identifies nothing, and identifying
     * a hull is the entire purpose of the search — a candidate that cannot supply the number
     * the caller came for is noise on a review screen.
     */
    private VesselParticulars readRow(Element link) {
        String imo = imoFrom(link.attr("href"));
        if (imo == null) return null;

        Element row = link.closest("tr");
        return new VesselParticulars(
                imo,
                text(link.selectFirst("div.slna")),
                text(link.selectFirst("div.slty")),
                // The flag is only ever in the tooltip of its icon; there is no text for it.
                attr(link.selectFirst("div.flag-icon"), "title"),
                integer(cell(row, "td.v3")),
                decimal(cell(row, "td.v4")),
                decimal(cell(row, "td.v5")),
                dimension(cell(row, "td.v6"), 1),
                dimension(cell(row, "td.v6"), 2),
                props.getDetailUrlPrefix() + imo);
    }

    private static String imoFrom(String href) {
        if (href == null) return null;
        Matcher m = DETAILS_IMO.matcher(href);
        return m.find() ? m.group(1) : null;
    }

    private static String cell(Element row, String selector) {
        return row == null ? null : text(row.selectFirst(selector));
    }

    private static String text(Element e) {
        if (e == null) return null;
        String t = e.text().trim();
        return t.isEmpty() ? null : t;
    }

    private static String attr(Element e, String name) {
        if (e == null) return null;
        String v = e.attr(name).trim();
        return v.isEmpty() ? null : v;
    }

    private static Integer integer(String raw) {
        BigDecimal d = decimal(raw);
        return d == null ? null : d.intValue();
    }

    /**
     * A figure from a cell, or null.
     *
     * <p>Separators are stripped rather than interpreted. The cells here carry plain integers
     * — a deadweight, a tonnage, a year — so there is no decimal point to lose and no
     * European-thousands ambiguity to get wrong. A cell holding "-" or a dash for "not known"
     * simply yields null, which is what it means.
     */
    private static BigDecimal decimal(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9.]", "");
        if (digits.isEmpty() || digits.equals(".")) return null;
        try {
            BigDecimal value = new BigDecimal(digits);
            return value.signum() == 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "112 / 15" — group 1 is length, group 2 is beam. */
    private static BigDecimal dimension(String raw, int group) {
        if (raw == null) return null;
        Matcher m = LENGTH_BEAM.matcher(raw);
        return m.find() ? new BigDecimal(m.group(group)) : null;
    }
}
