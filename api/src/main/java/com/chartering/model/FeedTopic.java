package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Something the desk wants the market read for — "Handysize freight, Med / Black Sea", "grain
 * out of the Danube".
 *
 * <p>The keywords are what an item has to mention to be read for the topic at all. They are the
 * cheap half of relevance: a keyword test costs nothing, and every item it rules out is a slice
 * of a context window that did not have to be spent on it.
 */
@Getter
@Setter
@Entity
@Table(name = "feed_topics")
public class FeedTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Comma-separated, as typed. */
    @Column(columnDefinition = "text")
    private String keywords;

    @Column(nullable = false)
    private boolean selected = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** The keywords as a list: trimmed, blanks dropped, case kept for display. */
    public List<String> keywordList() {
        if (keywords == null || keywords.isBlank()) return List.of();
        return Arrays.stream(keywords.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }
}
