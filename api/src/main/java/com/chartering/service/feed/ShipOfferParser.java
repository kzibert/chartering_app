package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.model.FeedSource;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShipOffer: cargo and tonnage offers, one page each, linked from the site's front page.
 *
 * <p>The front page shows a truncated teaser of the newest offers; the offer page carries the
 * whole email, server-rendered, with its sender, date and subject in a definition list above it.
 * So a fetch reads the front page for links and opens only the offers it has not stored — the
 * offer's uuid is its identity — one at a time with a pause between, capped per fetch.
 */
@Component
@RequiredArgsConstructor
public class ShipOfferParser implements WebsiteParser {

    private static final Pattern OFFER_PATH = Pattern.compile("/en/(cargo|tonnage)-offer/([0-9a-f-]{36})");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy, hh:mm a", Locale.ENGLISH);

    private final FeedHttp http;
    private final FeedProperties props;

    @Override
    public String key() {
        return "shipoffer";
    }

    @Override
    public String label() {
        return "ShipOffer cargo and tonnage offers";
    }

    @Override
    public String exampleUrl() {
        return "https://www.shipoffer.com/en";
    }

    @Override
    public FeedReader.ReadResult read(FeedSource source, Predicate<String> alreadySeen) {
        FeedHttp.Page listing = http.get(source.getUrl());
        List<OfferLink> links = parseListing(listing.body());
        if (links.isEmpty()) {
            throw new FeedReader.FeedFetchException("No offer links found on " + source.getUrl()
                    + " — the page layout may have changed.");
        }
        URI base = URI.create(listing.finalUrl());
        List<FeedReader.FetchedItem> items = new ArrayList<>();
        int opened = 0;
        for (OfferLink link : links) {
            if (alreadySeen.test(link.externalId())) continue;
            if (opened >= props.getMaxDetailPagesPerFetch()) break; // the rest wait for the next fetch
            if (opened > 0) http.pause();
            opened++;
            String url = base.resolve(link.path()).toString();
            FeedReader.FetchedItem item = parseDetail(http.get(url).body(), link, url, props.getMaxItemChars());
            if (item != null) items.add(item);
        }
        return new FeedReader.ReadResult(items, null, null, false);
    }

    public record OfferLink(String kind, String uuid, String path) {
        public String externalId() {
            return kind + "/" + uuid;
        }
    }

    public static List<OfferLink> parseListing(String html) {
        Set<String> seen = new LinkedHashSet<>();
        List<OfferLink> links = new ArrayList<>();
        Document doc = Jsoup.parse(html == null ? "" : html);
        for (Element a : doc.select("a[href]")) {
            Matcher m = OFFER_PATH.matcher(a.attr("href"));
            if (m.find() && seen.add(m.group(2))) {
                links.add(new OfferLink(m.group(1), m.group(2), m.group()));
            }
        }
        return links;
    }

    public static FeedReader.FetchedItem parseDetail(String html, OfferLink link, String url, int maxChars) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        String from = null;
        String date = null;
        String subject = null;
        Element dl = null;
        for (Element dt : doc.select("dl dt")) {
            Element dd = dt.nextElementSibling();
            if (dd == null) continue;
            switch (dt.text().strip().toLowerCase(Locale.ROOT)) {
                case "from" -> { from = dd.text(); dl = dt.closest("dl"); }
                case "date" -> date = dd.text();
                case "subject" -> subject = dd.text();
                default -> { }
            }
        }

        // The email sits in the scrolling box after the definition list.
        Element body = null;
        if (dl != null) {
            Element container = dl.parent();
            while (container != null && body == null) {
                Element next = container.nextElementSibling();
                if (next != null && !FeedText.fromElement(next).isBlank()) body = next;
                container = container.parent();
                if (container != null && container.tagName().equals("article")) break;
            }
        }
        if (body == null) body = doc.selectFirst("div[class*=overflow-auto]");
        String text = FeedText.truncate(FeedText.fromElement(body), maxChars);
        if (text.isBlank()) return null;

        Element h1 = doc.selectFirst("h1");
        String title = subject != null ? subject : h1 == null ? null : h1.text();
        return new FeedReader.FetchedItem(link.externalId(), parseDate(date),
                FeedText.truncate(title, 500), text, url, senderName(from));
    }

    /** "FIRM - PERSON <c*****@f****.com>": the address is masked on the page, so only the name is kept. */
    private static String senderName(String from) {
        if (from == null) return null;
        int lt = from.indexOf('<');
        String name = (lt >= 0 ? from.substring(0, lt) : from).strip();
        return name.isEmpty() ? null : FeedText.truncate(name, 300);
    }

    private static LocalDateTime parseDate(String raw) {
        if (raw == null) return null;
        try {
            return LocalDateTime.parse(raw.strip(), DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
