package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Whether "Ask the desk" exists on this deployment. Always answers, because the UI asks it before it
 * decides whether to show the tab at all.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskStatusResponse(
        /** PARSER_ENABLED and EMBEDDING_URL are both set. False means the feature is not part of this deployment. */
        boolean enabled,
        /** The embedding model the desk's text is indexed with, where the feature is enabled. */
        String model) {
}
