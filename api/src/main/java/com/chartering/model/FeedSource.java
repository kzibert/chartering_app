package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Somewhere outside this app that talks about the market: a Telegram channel, an RSS feed, or
 * a website with a parser of its own.
 *
 * <p>Not audited. A source is a bookmark with fetch bookkeeping on it, and every fetch rewrites
 * that bookkeeping — logging it would record a timer, not a decision.
 */
@Getter
@Setter
@Entity
@Table(name = "feed_sources")
public class FeedSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FeedSourceKind kind;

    /**
     * For a Telegram channel, its handle normalised to {@code https://t.me/s/<handle>}; for a
     * feed or a site, the address as given. Unique, so the same channel cannot be read twice
     * under two names.
     */
    @Column(nullable = false, length = 1000)
    private String url;

    /** Which site parser reads a {@link FeedSourceKind#WEBSITE}. Null for the other kinds. */
    @Column(name = "parser_key", length = 50)
    private String parserKey;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 500)
    private String etag;

    @Column(name = "last_modified", length = 100)
    private String lastModified;

    @Column(name = "last_fetched_at")
    private LocalDateTime lastFetchedAt;

    /** Why the last fetch failed; null once one succeeds. */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_new_items")
    private Integer lastNewItems;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
