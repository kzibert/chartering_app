package com.chartering.service.feed;

import com.chartering.model.FeedSummary;
import com.chartering.model.FeedSummaryStatus;
import com.chartering.model.FeedSummaryStrategy;
import com.chartering.repository.FeedItemRepository;
import com.chartering.repository.FeedSummaryRepository;
import com.chartering.repository.FeedTopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

/**
 * The summary rows' writes, each its own short transaction.
 *
 * <p>A bean apart from the run for the {@code EmailParseRunner} reason, and short for the fetch
 * runner's: a topic is minutes of model calls, and none of them should happen with a database
 * connection held. The row is written RUNNING first so the screen shows the topic in progress,
 * and so a process killed halfway leaves a row that says where it stopped.
 */
@Component
@RequiredArgsConstructor
public class FeedSummaryRecorder {

    private final FeedSummaryRepository summaries;
    private final FeedTopicRepository topics;
    private final FeedItemRepository items;

    @Transactional
    public Long start(UUID runId, Long topicId, String topicName, int contextWindow) {
        FeedSummary s = new FeedSummary();
        s.setRunId(runId);
        s.setTopic(topicId == null ? null : topics.getReferenceById(topicId));
        s.setTopicName(topicName);
        s.setStatus(FeedSummaryStatus.RUNNING);
        s.setContextWindow(contextWindow);
        s.setCreatedAt(LocalDateTime.now());
        return summaries.save(s).getId();
    }

    public record Outcome(String content, FeedSummaryStrategy strategy, int levels, int llmCalls,
                          int itemsConsidered, int itemsUsed, int itemsDropped, int promptTokens,
                          int completionTokens, LocalDateTime periodFrom, LocalDateTime periodTo,
                          String systemPrompt, String model, int durationMs, Collection<Long> itemIds) {
    }

    @Transactional
    public void finish(Long summaryId, Outcome o) {
        FeedSummary s = summaries.findById(summaryId).orElseThrow();
        s.setStatus(FeedSummaryStatus.DONE);
        s.setContent(o.content());
        s.setStrategy(o.strategy());
        s.setLevels(o.levels());
        s.setLlmCalls(o.llmCalls());
        s.setItemsConsidered(o.itemsConsidered());
        s.setItemsUsed(o.itemsUsed());
        s.setItemsDropped(o.itemsDropped());
        s.setPromptTokens(o.promptTokens());
        s.setCompletionTokens(o.completionTokens());
        s.setPeriodFrom(o.periodFrom());
        s.setPeriodTo(o.periodTo());
        s.setSystemPrompt(o.systemPrompt());
        s.setModel(FeedText.truncate(o.model(), 300));
        s.setDurationMs(o.durationMs());
        s.getItems().clear();
        if (!o.itemIds().isEmpty()) s.getItems().addAll(items.findAllById(o.itemIds()));
    }

    @Transactional
    public void fail(Long summaryId, String error, int llmCalls, int durationMs, String systemPrompt) {
        summaries.findById(summaryId).ifPresent(s -> {
            s.setStatus(FeedSummaryStatus.FAILED);
            s.setError(FeedText.truncate(error, 4_000));
            s.setLlmCalls(llmCalls);
            s.setDurationMs(durationMs);
            s.setSystemPrompt(systemPrompt);
        });
    }
}
