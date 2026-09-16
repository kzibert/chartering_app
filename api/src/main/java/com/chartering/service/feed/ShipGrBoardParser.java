package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.model.FeedSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * ship.gr's Open Cargoes and Open Ships boards — one layout, so one parser for both pages.
 *
 * <p>Each board is a single page of circulars pasted in as they arrived: a horizontal rule, a
 * date line ("14 September 2026"), then the email with its line breaks as {@code <br>}. There
 * are no ids and no links, so an entry's identity is a hash of its date and its words — the same
 * paste read on the next fetch hashes the same and is skipped.
 *
 * <p>The page carries an ETag and a Last-Modified, so an hourly fetch of a board nobody has
 * posted to since costs a 304.
 */
@Component
@RequiredArgsConstructor
public class ShipGrBoardParser implements WebsiteParser {

    private static final Pattern RULE = Pattern.compile("<hr\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_LINE = Pattern.compile("^\\d{1,2} [A-Za-z]+ \\d{4}$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final FeedHttp http;
    private final FeedProperties props;

    @Override
    public String key() {
        return "ship-gr-board";
    }

    @Override
    public String label() {
        return "ship.gr open cargoes / open ships board";
    }

    @Override
    public String exampleUrl() {
        return "http://www.ship.gr/shipbroker/cargo.html";
    }

    @Override
    public FeedReader.ReadResult read(FeedSource source, Predicate<String> alreadySeen) {
        FeedHttp.Page page = http.get(source.getUrl(), source.getEtag(), source.getLastModified());
        if (page.notModified()) return FeedReader.ReadResult.unchanged(page.etag(), page.lastModified());
        List<FeedReader.FetchedItem> items = parse(page.body(), source.getUrl(), props.getMaxItemChars());
        if (items.isEmpty()) {
            throw new FeedReader.FeedFetchException("No dated entries found on " + source.getUrl()
                    + " — the page layout may have changed.");
        }
        return new FeedReader.ReadResult(items, page.etag(), page.lastModified(), false);
    }

    public static List<FeedReader.FetchedItem> parse(String html, String url, int maxChars) {
        List<FeedReader.FetchedItem> items = new ArrayList<>();
        if (html == null) return items;
        for (String chunk : RULE.split(html)) {
            String text = FeedText.fromHtml(chunk);
            int newline = text.indexOf('\n');
            String first = (newline < 0 ? text : text.substring(0, newline)).strip();
            if (!DATE_LINE.matcher(first).matches()) continue; // the header, the footer, an advert
            LocalDate day;
            try {
                day = LocalDate.parse(first, DATE);
            } catch (DateTimeParseException e) {
                continue;
            }
            String body = newline < 0 ? "" : text.substring(newline + 1).strip();
            if (body.isBlank()) continue;
            items.add(new FeedReader.FetchedItem(FeedText.hash(first + "\n" + body),
                    day.atStartOfDay(), null, FeedText.truncate(body, maxChars), url, null));
        }
        return items;
    }
}
