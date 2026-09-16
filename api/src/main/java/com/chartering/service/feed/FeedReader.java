package com.chartering.service.feed;

import com.chartering.model.FeedSource;
import com.chartering.model.FeedSourceKind;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads one kind of source into items. One implementation per {@link FeedSourceKind}.
 *
 * <p>A reader returns what the source currently shows, including items already stored — telling
 * new from seen is the runner's job, against the database, so a reader stays a pure function of
 * a page and can be tested against a saved one.
 */
public interface FeedReader {

    FeedSourceKind kind();

    /**
     * @param alreadySeen asks whether an item id is stored, for readers that must decide whether
     *                    to spend another request on it (a detail page, an older page of posts)
     */
    ReadResult read(FeedSource source, java.util.function.Predicate<String> alreadySeen);

    /** One item as a source published it. */
    record FetchedItem(String externalId, LocalDateTime publishedAt, String title, String text,
                       String url, String author) {
    }

    /**
     * @param notModified the server answered 304 to the stored validators; {@code items} is empty
     *                    and means "nothing changed" rather than "nothing there"
     */
    record ReadResult(List<FetchedItem> items, String etag, String lastModified,
                      boolean notModified) {

        public static ReadResult unchanged(String etag, String lastModified) {
            return new ReadResult(List.of(), etag, lastModified, true);
        }
    }

    /** A source that could not be read, with a message a person can act on. */
    class FeedFetchException extends RuntimeException {
        public FeedFetchException(String message) {
            super(message);
        }

        public FeedFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
