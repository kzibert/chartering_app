package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One post, article or pasted circular as a source published it.
 *
 * <p>A copy, not a reference: the Telegram preview shows the last twenty posts and a board
 * rolls its entries off the bottom, so what a summary was written from has to be here to be
 * looked at afterwards.
 */
@Getter
@Setter
@Entity
@Table(name = "feed_items")
public class FeedItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private FeedSource source;

    /** The source's own identity for this item; the dedupe key within a source. */
    @Column(name = "external_id", nullable = false, length = 500)
    private String externalId;

    /** When the source says it was published; null where the page does not say. */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(length = 1000)
    private String url;

    @Column(length = 300)
    private String author;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt = LocalDateTime.now();
}
