package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.model.FeedSource;
import com.chartering.model.FeedSourceKind;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * RSS 2.0 and Atom, one general rule for every feed.
 *
 * <p>Jsoup's XML parser rather than a feed library: Jsoup is already here for the vessel lookup,
 * and a feed is two element names and a date format — Rome would be a dependency to read
 * {@code <item>} and {@code <entry>}.
 *
 * <p>The full article where the feed carries one ({@code content:encoded}, Atom {@code content})
 * and the excerpt where it does not. Several trade feeds send only a teaser; the summariser
 * works with what it is given, and the item's link is there for the rest.
 */
@Component
@RequiredArgsConstructor
public class RssReader implements FeedReader {

    private final FeedHttp http;
    private final FeedProperties props;

    @Override
    public FeedSourceKind kind() {
        return FeedSourceKind.RSS;
    }

    @Override
    public ReadResult read(FeedSource source, Predicate<String> alreadySeen) {
        FeedHttp.Page page = http.get(source.getUrl(), source.getEtag(), source.getLastModified());
        if (page.notModified()) return ReadResult.unchanged(page.etag(), page.lastModified());
        List<FetchedItem> items = parse(page.body(), props.getMaxItemChars());
        return new ReadResult(items, page.etag(), page.lastModified(), false);
    }

    /** A feed document into items. Static so a saved feed can be tested without a server. */
    public static List<FetchedItem> parse(String xml, int maxChars) {
        if (xml == null || xml.isBlank()) {
            throw new FeedFetchException("The feed was empty.");
        }
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        List<Element> entries = doc.getElementsByTag("item");
        boolean atom = entries.isEmpty();
        if (atom) entries = doc.getElementsByTag("entry");
        if (entries.isEmpty() && doc.getElementsByTag("rss").isEmpty()
                && doc.getElementsByTag("feed").isEmpty()) {
            throw new FeedFetchException("That address did not return an RSS or Atom feed.");
        }

        List<FetchedItem> items = new ArrayList<>();
        for (Element e : entries) {
            String title = FeedText.fromHtml(child(e, "title"));
            String link = atom ? atomLink(e) : child(e, "link");
            String body = first(child(e, "content:encoded"), child(e, "content"),
                    child(e, "description"), child(e, "summary"));
            String text = FeedText.truncate(FeedText.fromHtml(body), maxChars);
            if (text.isBlank()) text = title;
            if (text == null || text.isBlank()) continue;

            String id = first(child(e, "guid"), child(e, "id"), link, FeedText.hash(title + text));
            String date = first(child(e, "pubDate"), child(e, "published"), child(e, "updated"),
                    child(e, "dc:date"));
            String author = first(child(e, "dc:creator"), child(e, "author"));
            items.add(new FetchedItem(FeedText.truncate(id.strip(), 500), parseDate(date),
                    FeedText.truncate(emptyToNull(title), 500), text,
                    FeedText.truncate(emptyToNull(link), 1000),
                    FeedText.truncate(emptyToNull(FeedText.fromHtml(author)), 300)));
        }
        return items;
    }

    /** The text of the first child of that name; CDATA arrives as its raw contents. */
    private static String child(Element parent, String tag) {
        for (Element c : parent.children()) {
            if (c.tagName().equalsIgnoreCase(tag)) {
                String s = c.wholeText();
                // An Atom <author> holds <name>; its whole text is the name plus whitespace.
                return s == null ? null : s.strip();
            }
        }
        return null;
    }

    private static String atomLink(Element entry) {
        String any = null;
        for (Element l : entry.getElementsByTag("link")) {
            String rel = l.attr("rel");
            if (rel.isEmpty() || rel.equals("alternate")) return l.attr("href");
            if (any == null) any = l.attr("href");
        }
        return any;
    }

    static LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip();
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Atom and dc:date are ISO-8601.
        }
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // A feed with a date nobody can read still has items worth keeping.
        }
        return null;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
