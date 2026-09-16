package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything the Feed tab's header needs, in one call that answers on every deployment.
 *
 * <p>With analysis off the model fields are absent rather than false: that instance has no model
 * to be unreachable, and "Model server down" on the hosted Feed tab would be a false alarm.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedStatusResponse(
        boolean analysisEnabled,
        Boolean reachable,
        String modelUrl,
        long sources,
        long enabledSources,
        long items,
        long topics,
        long selectedTopics,
        boolean fetchRunning,
        OffsetDateTime lastFetchAt,
        OffsetDateTime nextFetchAt,
        String lastFetchMessage,
        boolean summaryRunning,
        Integer runTopicIndex,
        Integer runTopicCount,
        String runTopicName,
        String runStage,
        String lastRunMessage,
        OffsetDateTime lastRunAt,
        List<String> warnings) {
}
