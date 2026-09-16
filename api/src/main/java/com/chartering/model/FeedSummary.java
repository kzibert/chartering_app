package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * What the model made of one topic's items, and how it was made to fit.
 *
 * <p>The counts are part of the answer rather than diagnostics. A summary written from twelve
 * items of forty, because the rest did not fit under the call cap, says something different
 * from one written from all forty — and nothing in its prose would ever show it.
 */
@Getter
@Setter
@Entity
@Table(name = "feed_summaries")
public class FeedSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id")
    private FeedTopic topic;

    /** The topic's name when this ran, kept because the topic may be renamed or deleted. */
    @Column(name = "topic_name", nullable = false, length = 200)
    private String topicName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FeedSummaryStatus status = FeedSummaryStatus.RUNNING;

    @Column(columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private FeedSummaryStrategy strategy;

    private Integer levels;

    @Column(name = "llm_calls")
    private Integer llmCalls;

    @Column(name = "items_considered")
    private Integer itemsConsidered;

    @Column(name = "items_used")
    private Integer itemsUsed;

    @Column(name = "items_dropped")
    private Integer itemsDropped;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "context_window")
    private Integer contextWindow;

    @Column(name = "period_from")
    private LocalDateTime periodFrom;

    @Column(name = "period_to")
    private LocalDateTime periodTo;

    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    @Column(length = 300)
    private String model;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "feed_summary_items",
            joinColumns = @JoinColumn(name = "summary_id"),
            inverseJoinColumns = @JoinColumn(name = "item_id"))
    private Set<FeedItem> items = new LinkedHashSet<>();
}
