package com.chartering.dto;

import com.chartering.model.UserRole;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/** One account as the Admin screen lists it. Never carries the hash. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(
        Long id,
        String username,
        String displayName,
        UserRole role,
        boolean enabled,
        boolean mustChangePassword,
        boolean locked,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt,
        String createdBy,
        Long tenantId,
        String tenantName) {
}
