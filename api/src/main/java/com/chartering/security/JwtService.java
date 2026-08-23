package com.chartering.security;

import com.chartering.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Mints and verifies the bearer tokens. HS256 over a single shared secret: there is one
 * issuer and one audience — this application — so a signature anyone holding the secret can
 * both make and check is exactly the right shape, and an asymmetric key would only add a
 * key pair to manage.
 *
 * <p>The token carries the username and an expiry and nothing else. Deliberately: a JWT is
 * readable by anyone holding it (it is signed, not encrypted), so it is the wrong place for
 * anything the browser should not see, and this application has no roles or per-user data to
 * put there anyway.
 */
@Service
@Slf4j
public class JwtService {

    private final AuthProperties props;
    private final SecretKey key;

    /**
     * The key is resolved here rather than in an {@code @PostConstruct}: it depends on
     * nothing but the properties, so there is no reason for it to be mutable or for a
     * half-built instance to exist. A bad key therefore fails bean creation, which fails
     * startup — which is the intent.
     */
    public JwtService(AuthProperties props) {
        this.props = props;
        this.key = resolveKey(props);
    }

    private SecretKey resolveKey(AuthProperties props) {
        String secret = props.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            // Random key: every token this process issues is valid, and every token issued
            // by the previous process is not. That is the safe failure — the alternative
            // would be a hard-coded default key, which is no key at all.
            log.warn("chartering.auth.jwt-secret is not set — signing with a key generated at "
                    + "startup. Logins will not survive a restart. Set JWT_SECRET (32+ chars) "
                    + "on any instance that is not a throwaway.");
            return Jwts.SIG.HS256.key().build();
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // Fail at startup rather than at the first login: a key this short is a
            // configuration mistake, and an app that starts and then rejects every login is
            // a much harder thing to diagnose than one that refuses to start.
            throw new IllegalStateException(
                    "chartering.auth.jwt-secret must be at least 32 characters (HS256 needs a "
                            + "256-bit key); got " + bytes.length);
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /** A signed token for {@code username}, valid for the configured TTL. */
    public String issue(String username) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(props.getTokenTtlMinutes() * 60);
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /** When the token this call would issue expires — the UI shows it and schedules on it. */
    public Instant expiryOfNewToken() {
        return Instant.now().plusSeconds(props.getTokenTtlMinutes() * 60);
    }

    /**
     * The username inside a valid token, or empty if it is expired, tampered with, signed
     * with another key, or simply not a JWT. Every one of those is the same answer to the
     * caller — "not authenticated" — so they are not distinguished here.
     */
    public Optional<String> subjectOf(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected token: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
