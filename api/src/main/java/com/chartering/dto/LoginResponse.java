package com.chartering.dto;

import java.time.Instant;

/**
 * What a successful login returns. The expiry is included so the browser can show a session
 * as expired without waiting for a request to come back 401 — the token itself carries the
 * same value, but making the client decode a JWT to read it would be silly.
 *
 * <p>{@code mustChangePassword} is the browser's cue to show the change-password screen
 * rather than the app; the server refuses everything else to such a session regardless.
 */
public record LoginResponse(String token, String username, Instant expiresAt, boolean mustChangePassword) {
}
