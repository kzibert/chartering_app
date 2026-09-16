package com.chartering.service.feed;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each reader against a page shaped like the real one. The layouts are copied from the live
 * sources; the content is invented — firms, ships and addresses — because a test file is
 * published with the repository.
 */
class FeedReadersTest {

    private static final int MAX = 40_000;

    // ------------------------------------------------------------------ RSS / Atom

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/">
            <channel><title>Example Shipping News</title>
              <item>
                <title>Coaster rates up on the Danube</title>
                <link>https://news.example/coasters-up/</link>
                <dc:creator><![CDATA[Desk]]></dc:creator>
                <pubDate>Tue, 15 Sep 2026 08:21:00 +0000</pubDate>
                <guid isPermaLink="false">https://news.example/?p=101</guid>
                <description><![CDATA[<p>Short teaser.</p>]]></description>
                <content:encoded><![CDATA[<p><strong>Corn to Egypt</strong> reached about $110/t.</p><p>Handysize stayed low.</p>]]></content:encoded>
              </item>
              <item>
                <title>Teaser only</title>
                <link>https://news.example/teaser/</link>
                <pubDate>Mon, 14 Sep 2026 10:00:00 +0000</pubDate>
                <description><![CDATA[Grain flows remain uneven.]]></description>
              </item>
            </channel></rss>
            """;

    @Test
    void rssTakesTheFullArticleWhereThereIsOneAndTheExcerptWhereNot() {
        List<FeedReader.FetchedItem> items = RssReader.parse(RSS, MAX);

        assertThat(items).hasSize(2);
        var first = items.get(0);
        assertThat(first.externalId()).isEqualTo("https://news.example/?p=101");
        assertThat(first.title()).isEqualTo("Coaster rates up on the Danube");
        assertThat(first.text()).isEqualTo("Corn to Egypt reached about $110/t.\n\nHandysize stayed low.");
        assertThat(first.author()).isEqualTo("Desk");
        assertThat(first.publishedAt()).isNotNull();
        assertThat(first.publishedAt().toLocalDate()).isBetween(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16));

        // No guid: the link is the id.
        assertThat(items.get(1).externalId()).isEqualTo("https://news.example/teaser/");
        assertThat(items.get(1).text()).isEqualTo("Grain flows remain uneven.");
    }

    @Test
    void atomEntriesReadTheSameWay() {
        String atom = """
                <?xml version="1.0" encoding="utf-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom"><title>Example</title>
                  <entry>
                    <title>Weekly dry bulk note</title>
                    <link rel="alternate" href="https://research.example/note-37"/>
                    <id>tag:research.example,2026:37</id>
                    <updated>2026-09-14T08:00:00Z</updated>
                    <content type="html">&lt;p&gt;Supramax firmer in the Atlantic.&lt;/p&gt;</content>
                  </entry>
                </feed>
                """;

        var items = RssReader.parse(atom, MAX);

        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.externalId()).isEqualTo("tag:research.example,2026:37");
            assertThat(i.url()).isEqualTo("https://research.example/note-37");
            assertThat(i.text()).isEqualTo("Supramax firmer in the Atlantic.");
            assertThat(i.publishedAt()).isNotNull();
        });
    }

    @Test
    void aPageThatIsNotAFeedIsSaidToBeOne() {
        assertThatThrownBy(() -> RssReader.parse("<html><body>Hello</body></html>", MAX))
                .isInstanceOf(FeedReader.FeedFetchException.class)
                .hasMessageContaining("RSS or Atom");
    }

    // ------------------------------------------------------------------ Telegram

    private static final String TELEGRAM = """
            <html><body>
            <div class="tgme_channel_info"></div>
            <div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="examplechannel/439">
              <div class="tgme_widget_message_bubble">
                <div class="tgme_widget_message_author"><a class="tgme_widget_message_owner_name"><span>EXAMPLE CHARTERING</span></a></div>
                <div class="tgme_widget_message_text" dir="auto">OPEN TONNAGE:<br/><br/>M/V EXAMPLE STAR 5,100 DWCC<br/>OPEN ISKENDERUN 22-25 SEPT</div>
                <div class="tgme_widget_message_footer"><a class="tgme_widget_message_date" href="https://t.me/examplechannel/439"><time datetime="2026-09-14T20:54:04+00:00">20:54</time></a></div>
              </div></div></div>
            <div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="examplechannel/440">
              <div class="tgme_widget_message_bubble"><div class="tgme_widget_message_photo_wrap"></div>
                <div class="tgme_widget_message_footer"><a class="tgme_widget_message_date" href="https://t.me/examplechannel/440"><time datetime="2026-09-14T21:00:00+00:00">21:00</time></a></div>
              </div></div></div>
            <div class="tgme_widget_message_wrap"><div class="tgme_widget_message" data-post="examplechannel/441">
              <div class="tgme_widget_message_bubble">
                <div class="tgme_widget_message_reply"><div class="tgme_widget_message_text">quoted older post</div></div>
                <div class="tgme_widget_message_text">Fixed, thanks all.</div>
              </div></div></div>
            </body></html>
            """;

    @Test
    void telegramKeepsLineBreaksAndSkipsPostsWithNothingToRead() {
        var page = TelegramReader.parsePage(TELEGRAM, MAX);

        assertThat(page.isChannel()).isTrue();
        assertThat(page.oldestPost()).isEqualTo(439L);
        assertThat(page.items()).extracting(FeedReader.FetchedItem::externalId)
                .containsExactly("examplechannel/439", "examplechannel/441"); // 440 is a photo with no caption
        var first = page.items().get(0);
        assertThat(first.text()).isEqualTo("OPEN TONNAGE:\n\nM/V EXAMPLE STAR 5,100 DWCC\nOPEN ISKENDERUN 22-25 SEPT");
        assertThat(first.url()).isEqualTo("https://t.me/examplechannel/439");
        assertThat(first.author()).isEqualTo("EXAMPLE CHARTERING");
        assertThat(first.publishedAt()).isNotNull();
        // A reply's quote is the other post's text, not this one's.
        assertThat(page.items().get(1).text()).isEqualTo("Fixed, thanks all.");
    }

    @Test
    void aChannelIsStoredUnderOneAddressHoweverItWasTyped() {
        assertThat(TelegramReader.normaliseUrl("@example_channel")).isEqualTo("https://t.me/s/example_channel");
        assertThat(TelegramReader.normaliseUrl("t.me/example_channel")).isEqualTo("https://t.me/s/example_channel");
        assertThat(TelegramReader.normaliseUrl("https://t.me/s/example_channel?before=10"))
                .isEqualTo("https://t.me/s/example_channel");
        assertThatThrownBy(() -> TelegramReader.normaliseUrl("https://example.com/channel"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ ship.gr

    private static final String SHIP_GR = """
            <div class="panel-heading"><b>OPEN CARGOES</b></div>
            <div class="panel-body"><table class="table">
            <hr style="border-style: inset; border-width:1px">14 September 2026<br>
            <br>Dear sirs
            <br>Pls offer firm for below:
            <br>Any Dwt 6-8,000 Mt
            <br>Steel scrap
            <br>Example Port/Other Port
            <br>Best Regards
            <hr style="border-style: inset; border-width: 1px">
            <ins class="adsbygoogle"></ins><script>(adsbygoogle = window.adsbygoogle || []).push({});</script>
            <hr style="border-style: inset; border-width: 1px">12 September 2026<br>
            <br>ABT 10,000 MTS SALT IN BULK
            <br>LAYCAN: 15-25.09.2026
            </table></div>
            """;

    @Test
    void theBoardSplitsIntoItsDatedEntriesAndIgnoresTheRest() {
        var items = ShipGrBoardParser.parse(SHIP_GR, "http://board.example/cargo.html", MAX);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).publishedAt()).isEqualTo(LocalDate.of(2026, 9, 14).atStartOfDay());
        assertThat(items.get(0).text()).startsWith("Dear sirs\nPls offer firm for below:\nAny Dwt 6-8,000 Mt");
        assertThat(items.get(1).text()).isEqualTo("ABT 10,000 MTS SALT IN BULK\nLAYCAN: 15-25.09.2026");
    }

    @Test
    void anEntryHashesTheSameOnTheNextFetch() {
        var once = ShipGrBoardParser.parse(SHIP_GR, "u", MAX);
        var again = ShipGrBoardParser.parse(SHIP_GR.replace("<br>Steel", "<br>  Steel"), "u", MAX);

        assertThat(again).extracting(FeedReader.FetchedItem::externalId)
                .containsExactlyElementsOf(once.stream().map(FeedReader.FetchedItem::externalId).toList());
    }

    // ------------------------------------------------------------------ ShipOffer

    @Test
    void shipOfferListsEachOfferOnce() {
        String home = """
                <article><a href="/en/tonnage-offer/54654883-b144-4e19-a126-77d3b5720bf0"><h3>EXAMPLE TONNAGE</h3></a>
                <a href="/en/tonnage-offer/54654883-b144-4e19-a126-77d3b5720bf0">View all</a></article>
                <article><a href="/en/cargo-offer/14d3ed06-8a0f-4478-92c0-ab81a6f717f3"><h3>EXAMPLE CARGO</h3></a></article>
                <a href="/en/tonnage-offer">All tonnage</a>
                """;

        var links = ShipOfferParser.parseListing(home);

        assertThat(links).extracting(ShipOfferParser.OfferLink::externalId).containsExactly(
                "tonnage/54654883-b144-4e19-a126-77d3b5720bf0", "cargo/14d3ed06-8a0f-4478-92c0-ab81a6f717f3");
    }

    @Test
    void anOfferPageGivesItsSubjectSenderDateAndTheWholeEmail() {
        String detail = """
                <article><a href="/en/tonnage-offer">Tonnage Offer</a><div><h1>EXAMPLE TONNAGE</h1>
                <div><h2>Source</h2><div><div><span>ShipOffer · View only</span></div>
                <div><dl>
                  <div><dt>From</dt><dd>EXAMPLE BULK CARRIERS - A. PERSON &lt;x******@e*****.example&gt;</dd></div>
                  <div><dt>Date</dt><dd>Sep 14, 2026, 07:26 AM</dd></div>
                  <div><dt>Subject</dt><dd>EXAMPLE HOME TONNAGES</dd></div>
                </dl></div>
                <div class="relative z-20 max-h-[75vh] overflow-auto px-4"><div>
                  <p>Please offer firm for;</p>
                  <p>- M/V EXAMPLE ONE&nbsp;&nbsp;&nbsp;&nbsp;4.552 DWT&nbsp;&nbsp;@ EAST MED 24/25 SEPT</p>
                </div></div></div></div></div></article>
                """;
        var link = new ShipOfferParser.OfferLink("tonnage", "54654883-b144-4e19-a126-77d3b5720bf0",
                "/en/tonnage-offer/54654883-b144-4e19-a126-77d3b5720bf0");

        var item = ShipOfferParser.parseDetail(detail, link, "https://offers.example/x", MAX);

        assertThat(item).isNotNull();
        assertThat(item.title()).isEqualTo("EXAMPLE HOME TONNAGES");
        assertThat(item.author()).isEqualTo("EXAMPLE BULK CARRIERS - A. PERSON");
        assertThat(item.publishedAt()).isEqualTo(LocalDate.of(2026, 9, 14).atTime(7, 26));
        assertThat(item.text()).isEqualTo("Please offer firm for;\n\n- M/V EXAMPLE ONE 4.552 DWT @ EAST MED 24/25 SEPT");
    }
}
