package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Generic id/name lookup row for filter dropdowns (regions, ports, tonnage categories). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LookupResponse(Long id, String name) {
}
