package com.chartering.dto;

import com.chartering.model.TenantStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/** One desk as the platform administrator's screen lists it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantResponse(Long id, String name, TenantStatus status, OffsetDateTime createdAt, long users) {
}
