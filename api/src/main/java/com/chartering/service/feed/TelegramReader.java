package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.model.FeedSource;
import com.chartering.model.FeedSourceKind;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A public Telegram channel, through the preview Telegram itself serves at
 * {@code https://t.me/s/<handle>}.
 *
 * <p><b>This is the reason Telegram is a source and WhatsApp is not.</b> Telegram publishes a
 * public channel's posts as a web page anyone can open; WhatsApp's channel page carries a name,
 * a description and a follower count, and every tool that reads the posts is a paired phone
 * number. No account is involved here at all.
 *
 * <p>The preview shows twenty posts a page, newest last. A fetch pages back with
 * {@code ?before=} until it meets a post already stored or runs out of pages — so a channel
 * added today brings its last hundred posts, and every fetch after that brings one page.
 */
@Component
@RequiredArgsConstructor
public class TelegramReader implements FeedReader {

    private static final Pattern URL_HANDLE = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?(?:t|telegram)\\.me/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,})/?(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_HANDLE = Pattern.compile("^@?([A-Za-z][A-Za-z0-9_]{3,})$");

    private final FeedHttp http;
    private final FeedProperties props;

    @Override
    public FeedSourceKind kind() {
        return FeedSourceKind.TELEGRAM;
    }

    /**
     * {@code @handle}, {@code t.me/handle} or {@code https://t.me/s/handle} as the one address a
     * source is stored under, so the same channel cannot be added twice spelled two ways.
     */
    public static String normaliseUrl(String input) {
        String s = input == null ? "" : input.strip();
        Matcher m = URL_HANDLE.matcher(s);
        if (!m.matches()) m = BARE_HANDLE.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "A Telegram source is a public channel: @handle or https://t.me/handle.");
        }
        return "https://t.me/s/" + m.group(1);
    }

    @Override
    public ReadResult read(FeedSource source, Predicate<String> alreadySeen) {
        String base = normaliseUrl(source.getUrl());
        List<FetchedItem> all = new ArrayList<>();
        String url = base;
        for (int pageNo = 0; pageNo < Math.max(1, props.getTelegramMaxPages()); pageNo++) {
            if (pageNo > 0) http.pause();
            Parsed page = parsePage(http.get(url).body(), props.getMaxItemChars());
            if (pageNo == 0 && page.items().isEmpty() && !page.isChannel()) {
                throw new FeedFetchException("t.me shows no public channel at " + base
                        + " — it may be private, a group, or a user rather than a channel.");
            }
            all.addAll(page.items());
            boolean caughtUp = page.items().stream().anyMatch(i -> alreadySeen.test(i.externalId()));
            if (caughtUp || page.oldestPost() == null || page.items().isEmpty()) break;
            url = base + "?before=" + page.oldestPost();
        }
        return new ReadResult(all, null, null, false);
    }

    /** One preview page: its posts, and the lowest post number on it for paging back. */
    public record Parsed(List<FetchedItem> items, Long oldestPost, boolean isChannel) {
    }

    public static Parsed parsePage(String html, int maxChars) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        boolean isChannel = doc.selectFirst(".tgme_channel_info, .tgme_widget_message") != null;
        List<FetchedItem> items = new ArrayList<>();
        Long oldest = null;
        for (Element msg : doc.select(".tgme_widget_message[data-post]")) {
            String post = msg.attr("data-post");
            Long number = postNumber(post);
            if (number != null && (oldest == null || number < oldest)) oldest = number;

            Element copy = msg.clone();
            // A reply quotes the post it answers inside its own bubble; that text is not this post.
            copy.select(".tgme_widget_message_reply").remove();
            Element textEl = copy.selectFirst(".tgme_widget_message_text");
            String text = FeedText.truncate(FeedText.fromElement(textEl), maxChars);
            if (text.isBlank()) continue; // a photo or a sticker with no caption says nothing to read

            Element time = copy.selectFirst("time[datetime]");
            Element link = copy.selectFirst("a.tgme_widget_message_date");
            Element author = copy.selectFirst(".tgme_widget_message_from_author, .tgme_widget_message_owner_name");
            items.add(new FetchedItem(post, time == null ? null : parseDate(time.attr("datetime")),
                    null, text, link == null ? null : link.attr("href"),
                    author == null ? null : FeedText.truncate(author.text(), 300)));
        }
        return new Parsed(items, oldest, isChannel);
    }

    private static Long postNumber(String dataPost) {
        int slash = dataPost.lastIndexOf('/');
        try {
            return Long.parseLong(dataPost.substring(slash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime parseDate(String iso) {
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
