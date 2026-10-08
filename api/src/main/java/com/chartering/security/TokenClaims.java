package com.chartering.security;

/**
 * What a verified token says. Only the version is checked against the row on each request;
 * the user id is what finds the row, and the tenant is carried for logs and debugging, never
 * trusted over what the row says.
 */
public record TokenClaims(Long userId, Long tenantId, int version) {
}
