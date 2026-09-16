package com.chartering.service.feed;

import com.chartering.model.FeedItem;
import com.chartering.model.FeedSource;
import com.chartering.model.FeedSourceKind;
import com.chartering.repository.FeedItemRepository;
import com.chartering.repository.FeedSourceRepository;
import com.chartering.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One source: read it, then store what is new.
 *
 * <p>A bean of its own so {@link #store} is a real transaction when the fetch service calls it —
 * the split {@code EmailParseRunner} makes. And the read is deliberately <i>outside</i> the
 * transaction: a Telegram channel paged back five times with pauses is ten seconds of network,
 * and a database connection held open for it is a connection the rest of the app is waiting for.
 */
@Component
public class FeedFetchRunner {

    private final FeedSourceRepository sources;
    private final FeedItemRepository items;
    private final Map<FeedSourceKind, FeedReader> readers = new EnumMap<>(FeedSourceKind.class);

    public FeedFetchRunner(FeedSourceRepository sources, FeedItemRepository items, List<FeedReader> readers) {
        this.sources = sources;
        this.items = items;
        readers.forEach(r -> this.readers.put(r.kind(), r));
    }

    public FeedReader.ReadResult read(Long sourceId) {
        FeedSource source = sources.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Feed source", sourceId));
        FeedReader reader = readers.get(source.getKind());
        if (reader == null) {
            throw new FeedReader.FeedFetchException("No reader for " + source.getKind());
        }
        return reader.read(source, id -> items.existsBySourceIdAndExternalId(sourceId, id));
    }

    /** @return how many items were new */
    @Transactional
    public int store(Long sourceId, FeedReader.ReadResult result) {
        FeedSource source = sources.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Feed source", sourceId));
        LocalDateTime now = LocalDateTime.now();
        source.setLastFetchedAt(now);
        source.setLastError(null);
        source.setEtag(result.etag());
        source.setLastModified(result.lastModified());
        if (result.notModified()) {
            source.setLastNewItems(0);
            return 0;
        }

        // A page can list one post twice (a pinned post, an offer linked from two sections).
        Map<String, FeedReader.FetchedItem> unique = new LinkedHashMap<>();
        for (FeedReader.FetchedItem i : result.items()) unique.putIfAbsent(i.externalId(), i);
        Set<String> existing = unique.isEmpty() ? Set.of()
                : new HashSet<>(items.findExistingExternalIds(sourceId, unique.keySet()));

        int added = 0;
        for (FeedReader.FetchedItem f : unique.values()) {
            if (existing.contains(f.externalId())) continue;
            FeedItem item = new FeedItem();
            item.setSource(source);
            item.setExternalId(f.externalId());
            item.setPublishedAt(f.publishedAt());
            item.setTitle(f.title());
            item.setText(f.text());
            item.setUrl(f.url());
            item.setAuthor(f.author());
            item.setContentHash(FeedText.hash(f.text()));
            item.setFetchedAt(now);
            items.save(item);
            added++;
        }
        source.setLastNewItems(added);
        return added;
    }

    /** Its own transaction, so recording why a source failed cannot be rolled back with the failure. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long sourceId, String message) {
        sources.findById(sourceId).ifPresent(s -> {
            s.setLastFetchedAt(LocalDateTime.now());
            s.setLastError(FeedText.truncate(message, 2_000));
            s.setLastNewItems(null);
        });
    }
}
