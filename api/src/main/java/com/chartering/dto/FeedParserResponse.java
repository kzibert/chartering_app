package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A site parser the WEBSITE kind can use. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedParserResponse(String key, String label, String exampleUrl) {
}
