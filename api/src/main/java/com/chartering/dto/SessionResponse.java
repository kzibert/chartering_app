package com.chartering.dto;

/**
 * GET /api/v1/auth/me — who the bearer token belongs to. The UI calls it once on load to
 * decide whether a token it found in storage is still good, which is cheaper and clearer
 * than firing a real query and interpreting the failure.
 */
public record SessionResponse(String username) {
}
