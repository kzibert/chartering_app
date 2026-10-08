package com.chartering.dto;

import com.chartering.model.UserRole;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GET /api/v1/auth/me — who the bearer token belongs to. The UI calls it once on load to
 * decide whether a token it found in storage is still good, which is cheaper and clearer
 * than firing a real query and interpreting the failure. It is also where the UI learns the
 * role (which admin screens to offer) and the desk (whose data this is).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionResponse(
        Long userId,
        String username,
        String displayName,
        UserRole role,
        Long tenantId,
        String tenantName,
        boolean mustChangePassword) {
}
