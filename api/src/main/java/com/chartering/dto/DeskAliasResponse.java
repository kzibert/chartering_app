package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One of the desk's own spellings: {@code alias} read as the port or trade area named.
 *
 * @param kind {@code PORT} or {@code AREA}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeskAliasResponse(String kind, Long id, String alias, Long targetId, String targetName) {
}
