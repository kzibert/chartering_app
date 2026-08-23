package com.chartering.service;

import com.chartering.config.AuthProperties;
import com.chartering.dto.LoginRequest;
import com.chartering.dto.LoginResponse;
import com.chartering.exception.AuthNotConfiguredException;
import com.chartering.exception.AuthenticationFailedException;
import com.chartering.security.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Checks the one credential and hands back a token.
 *
 * <p>The password is compared as a BCrypt hash whichever form it was configured in: a hash
 * is used as given, a plaintext value is hashed once here at startup and the plaintext is
 * not kept. That keeps one comparison path rather than two, and means the constant-time
 * property of BCrypt's check applies either way.
 */
@Service
@Slf4j
public class AuthService {

    private final AuthProperties props;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /** The hash every login is checked against. Null when nothing was configured. */
    private final String expectedHash;

    /** Consecutive failures, and when the lockout they caused ends. Single user, single counter. */
    private int failedAttempts;
    private Instant lockedUntil;

    public AuthService(AuthProperties props, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.props = props;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.expectedHash = resolveHash(props, passwordEncoder);
    }

    private static String resolveHash(AuthProperties props, PasswordEncoder encoder) {
        String hash = props.getPasswordHash();
        if (hash != null && !hash.isBlank()) {
            return hash.trim();
        }
        String plain = props.getPassword();
        if (plain != null && !plain.isBlank()) {
            log.info("Auth: password configured in plaintext and hashed at startup. Prefer "
                    + "AUTH_PASSWORD_HASH on a deployed instance — see the README.");
            return encoder.encode(plain);
        }
        // Not fatal on purpose: an app that refuses to start is worse to diagnose than one
        // that starts and says, at the login screen, exactly what is missing.
        log.error("Auth: no password configured. Set AUTH_PASSWORD (or AUTH_PASSWORD_HASH) — "
                + "until then every login is refused and the application cannot be used.");
        return null;
    }

    /** Whether a credential exists at all; the login endpoint answers differently without one. */
    public boolean isConfigured() {
        return expectedHash != null;
    }

    /**
     * Verify and issue. Throws {@link AuthenticationFailedException} for a wrong username,
     * a wrong password or a lockout — the caller turns all three into the same 401, because
     * telling an attacker which of the three it was is how a username gets confirmed.
     */
    public synchronized LoginResponse login(LoginRequest request) {
        if (!isConfigured()) {
            throw new AuthNotConfiguredException(
                    "No password is configured on the server. Set AUTH_PASSWORD and restart.");
        }
        if (lockedUntil != null && Instant.now().isBefore(lockedUntil)) {
            long seconds = Duration.between(Instant.now(), lockedUntil).toSeconds() + 1;
            throw new AuthenticationFailedException(
                    "Too many failed attempts. Try again in " + seconds + "s.");
        }

        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();

        // The password is checked even when the username is already wrong. Skipping it would
        // make a wrong username return measurably faster than a wrong password, which is
        // enough to enumerate the username one guess at a time.
        boolean passwordOk = passwordEncoder.matches(password, expectedHash);
        boolean usernameOk = props.getUsername() != null
                && props.getUsername().trim().equalsIgnoreCase(username);

        if (!usernameOk || !passwordOk) {
            registerFailure();
            throw new AuthenticationFailedException("Wrong username or password.");
        }

        failedAttempts = 0;
        lockedUntil = null;
        log.info("Auth: login for '{}'", username);
        return new LoginResponse(
                jwtService.issue(props.getUsername()),
                props.getUsername(),
                jwtService.expiryOfNewToken());
    }

    private void registerFailure() {
        failedAttempts++;
        int max = props.getMaxFailedAttempts();
        if (max > 0 && failedAttempts >= max) {
            lockedUntil = Instant.now().plusSeconds(props.getLockoutSeconds());
            failedAttempts = 0;
            log.warn("Auth: {} failed logins — login locked until {}", max, lockedUntil);
        }
    }
}
