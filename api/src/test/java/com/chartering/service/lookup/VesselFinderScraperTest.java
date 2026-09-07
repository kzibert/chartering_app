package com.chartering.service.lookup;

import com.chartering.config.VesselLookupProperties;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a search page's result rows.
 *
 * <p>Against a saved fragment rather than the live site. The parse is the half of this that
 * breaks and the half worth pinning; a test that made the real request would be flaky, slow,
 * and rude to a server this project has no arrangement with — and it would fail for reasons
 * that have nothing to do with the code.
 *
 * <p><b>What it cannot do is notice that the page has changed</b>, and nothing can: a
 * snapshot test passes forever against a page that was rebuilt this morning. That is the
 * standing cost of reading somebody else's markup, and it is why the scraper logs when a
 * search returns no rows at all — the one symptom that distinguishes "no such ship" from
 * "this parser now reads nothing off anything".
 */
class VesselFinderScraperTest {

    /**
     * The shape of a real result row, trimmed to the parts that carry anything.
     *
     * <p>Two hulls: one complete, and one with no IMO in its link — which is the row the
     * parser has to drop, because a candidate that cannot supply the number the caller came
     * for is noise on a review screen.
     */
    private static final String PAGE = """
            <html><body><table><tbody>
            <tr>
              <td class="v2"><a class="ship-link" href="/vessels/details/9014561">
                <div class="flag-icon-med flag-icon" title="Panama"></div>
                <div class="sli">
                  <div class="slna">HACI HILMI-II</div>
                  <div class="slty">General Cargo Ship</div>
                </div>
              </a></td>
              <td class="v3 hidden-mobile">1992</td>
              <td class="v4 hidden-mobile">3,989</td>
              <td class="v5 hidden-mobile">6977</td>
              <td class="v6 hidden-mobile">112 / 15</td>
            </tr>
            <tr>
              <td class="v2"><a class="ship-link" href="/vessels/no-details-here">
                <div class="sli"><div class="slna">MYSTERY SHIP</div></div>
              </a></td>
              <td class="v3 hidden-mobile">2001</td>
            </tr>
            </tbody></table></body></html>
            """;

    private final VesselFinderScraper scraper = new VesselFinderScraper(new VesselLookupProperties());

    @Test
    void readsAHullOutOfOneResultRow() {
        List<VesselParticulars> found = scraper.parse(Jsoup.parse(PAGE));

        assertThat(found).hasSize(1);
        VesselParticulars v = found.get(0);
        // The IMO is in the link, not in a cell — it is the only place the page states it.
        assertThat(v.imo()).isEqualTo("9014561");
        assertThat(v.name()).isEqualTo("HACI HILMI-II");
        assertThat(v.vesselType()).isEqualTo("General Cargo Ship");
        // The flag is only ever in the tooltip of its icon; there is no text for it.
        assertThat(v.flag()).isEqualTo("Panama");
        assertThat(v.yearBuilt()).isEqualTo(1992);
        assertThat(v.grossTonnage()).isEqualByComparingTo("3989");
        assertThat(v.deadweightTonnage()).isEqualByComparingTo("6977");
        assertThat(v.lengthM()).isEqualByComparingTo("112");
        assertThat(v.beamM()).isEqualByComparingTo("15");
        // Nothing is stored without a page a person can open and check.
        assertThat(v.sourceUrl()).endsWith("/9014561");
    }

    @Test
    void dropsARowThatCannotSupplyAnImo() {
        // The whole point of the search is the number. A row without one identifies nothing.
        assertThat(scraper.parse(Jsoup.parse(PAGE)))
                .extracting(VesselParticulars::name)
                .doesNotContain("MYSTERY SHIP");
    }

    @Test
    void readsNothingRatherThanGuessingFromAPageItDoesNotRecognise() {
        // What a rebuilt page, a block page or a captcha looks like from here. Empty is the
        // honest answer; the caller records NO_MATCH and the scraper logs that it saw no rows.
        assertThat(scraper.parse(Jsoup.parse("<html><body><h1>Just a moment…</h1></body></html>")))
                .isEmpty();
    }

    @Test
    void stopsAtTheConfiguredNumberOfCandidates() {
        VesselLookupProperties capped = new VesselLookupProperties();
        capped.setMaxCandidates(1);

        StringBuilder many = new StringBuilder("<html><body><table><tbody>");
        for (int i = 0; i < 5; i++) {
            many.append("<tr><td><a class=\"ship-link\" href=\"/vessels/details/900000")
                    .append(i).append("\"><div class=\"slna\">SHIP ").append(i)
                    .append("</div></a></td><td class=\"v5\">5000</td></tr>");
        }
        many.append("</tbody></table></body></html>");

        assertThat(new VesselFinderScraper(capped).parse(Jsoup.parse(many.toString()))).hasSize(1);
    }
}
