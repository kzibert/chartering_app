package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedTopicResponse(Long id, String name, List<String> keywords, boolean selected, int sortOrder) {
}
