package com.chartering.dto;

import com.chartering.model.FeedSummaryStatus;
import com.chartering.model.FeedSummaryStrategy;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One topic's summary. {@code items} is present only on the detail endpoint — a list of summaries
 * carrying every item behind each would be the whole collection over again.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedSummaryResponse(
        Long id,
        UUID runId,
        Long topicId,
        String topicName,
        FeedSummaryStatus status,
        String content,
        FeedSummaryStrategy strategy,
        Integer levels,
        Integer llmCalls,
        Integer itemsConsidered,
        Integer itemsUsed,
        Integer itemsDropped,
        Integer promptTokens,
        Integer completionTokens,
        Integer contextWindow,
        LocalDateTime periodFrom,
        LocalDateTime periodTo,
        String systemPrompt,
        String model,
        String error,
        Integer durationMs,
        LocalDateTime createdAt,
        List<FeedItemResponse> items) {
}
