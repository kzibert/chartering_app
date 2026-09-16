package com.chartering.service.feed;

import com.chartering.model.FeedItem;
import com.chartering.model.FeedSource;
import com.chartering.model.FeedSourceKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedItemSelectorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 12, 0);
    private static long ids = 1;

    private static FeedItem item(String sourceName, String title, String text, LocalDateTime published) {
        FeedSource source = new FeedSource();
        source.setName(sourceName);
        source.setKind(FeedSourceKind.RSS);
        FeedItem i = new FeedItem();
        i.setId(ids++);
        i.setSource(source);
        i.setTitle(title);
        i.setText(text);
        i.setContentHash(FeedText.hash(text));
        i.setPublishedAt(published);
        i.setFetchedAt(NOW);
        return i;
    }

    @Test
    void onlyItemsMentioningAKeywordAreKeptAndTheyAreRankedByHitsAndAge() {
        var old = item("A", null, "Handysize rates in the Black Sea eased again.", NOW.minusDays(6));
        var fresh = item("B", "Handysize update", "Handysize owners defend prompt positions in the Black Sea.", NOW.minusHours(3));
        var offTopic = item("C", null, "Container lines add capacity to Asia.", NOW.minusHours(1));

        var selection = FeedItemSelector.select(List.of(old, fresh, offTopic), List.of("handysize", "black sea"), NOW);

        assertThat(selection.ranked()).extracting(FeedItemSelector.Candidate::itemId)
                .containsExactly(fresh.getId(), old.getId());
        assertThat(selection.irrelevant()).isEqualTo(1);
        assertThat(selection.considered()).isEqualTo(3);
    }

    @Test
    void theSameCircularOnTwoBoardsIsReadOnce() {
        String circular = "Dear sirs, pls offer firm for 6-8,000 mt steel scrap, Example Port / Other Port, spot laycan, 1.25 pct.";
        var a = item("Board one", null, circular, NOW.minusHours(2));
        var b = item("Board two", null, circular.replace(", ", ",  "), NOW.minusHours(1));
        var reposted = item("Channel", null, "Good morning!\n" + circular, NOW);

        var selection = FeedItemSelector.select(List.of(a, b, reposted), List.of("steel scrap"), NOW);

        // a and b hash the same; the repost differs by a greeting but opens with the same substance
        // only after it, so it survives - the near-duplicate test is on the opening, deliberately narrow.
        assertThat(selection.duplicates()).isEqualTo(1);
        assertThat(selection.ranked()).hasSize(2);
    }

    @Test
    void signaturesContactLinesAndLongUrlsAreNotSpentTokensOn() {
        String raw = """
                Pls offer firm for below:
                Any Dwt 6-8,000 Mt
                Steel scrap, Example Port/Other Port
                See https://offers.example/very/long/path?with=query for details
                T: +00 00 000 000
                E: desk@firm.example
                Best Regards
                A. Person
                EXAMPLE CHARTERING AND BROKERS
                """;

        String cleaned = FeedItemSelector.clean(raw);

        assertThat(cleaned).isEqualTo("""
                Pls offer firm for below:
                Any Dwt 6-8,000 Mt
                Steel scrap, Example Port/Other Port
                See offers.example for details""");
    }

    @Test
    void aTopicWithNoKeywordsIsMatchedOnTheWordsOfItsName() {
        assertThat(FeedItemSelector.terms("Grain cargoes, Danube", List.of()))
                .containsExactly("Grain", "cargoes", "Danube");
        assertThat(FeedItemSelector.terms("Anything", List.of("coaster"))).containsExactly("coaster");
    }

    @Test
    void theBlockCarriesItsSourceAndDateForAttribution() {
        var c = new FeedItemSelector.Candidate(1L, "Example News", LocalDateTime.of(2026, 9, 14, 9, 0),
                "Rates up", "Corn $110/t.", 1);

        assertThat(c.block()).isEqualTo("[Example News, 14 Sep 2026] Rates up\nCorn $110/t.");
    }
}
