package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The one account that can use this application, and the key its tokens are signed with.
 *
 * <p><b>Why one user and not a users table.</b> This is a single-desk application: the
 * database has no concept of an owner, every row is visible to whoever is logged in, and
 * nothing in it is attributed to an account. A users table would therefore add a schema, a
 * migration and an admin screen while changing nothing about who can see what. The login
 * exists to stop the internet reaching the data, and one credential does that. If a second
 * person ever needs their own account, this class is what grows a repository behind it —
 * nothing else in the app asks who the caller is.
 *
 * <p>Everything here comes from the environment. Nothing is stored in the database, so
 * rotating the password or the signing key is an environment change and a restart.
 */
@Component
@ConfigurationProperties(prefix = "chartering.auth")
@Data
public class AuthProperties {

    /** The login name. Not an email address unless you make it one — it is only compared. */
    private String username = "admin";

    /**
     * BCrypt hash of the password, and the form to prefer: set this and the plaintext never
     * exists anywhere — not in {@code .env}, not in a Render environment group, not in a
     * shell history. Generate one with the helper documented in the README.
     *
     * <p>Takes precedence over {@link #password} when both are set.
     */
    private String passwordHash;

    /**
     * Plaintext password, hashed once at startup and never held in that form afterwards.
     * The convenient option for local work; on a deployed instance prefer
     * {@link #passwordHash}, since anything that can read the environment can read this.
     */
    private String password;

    /**
     * HS256 signing key, and the single secret that decides whether a token is genuine.
     * Anyone holding it can mint a token for any user, so it belongs in the platform's
     * secret store, never in the repo.
     *
     * <p>At least 32 characters — HS256 requires a 256-bit key and jjwt refuses a shorter
     * one rather than quietly weakening the signature. Left blank, the app generates a
     * random key at startup: safe, but it changes on every restart, so every existing
     * session is invalidated by a redeploy. Fine for dev, wrong for a deployment, and the
     * startup log says so.
     */
    private String jwtSecret;

    /**
     * How long a token stays valid. Long enough to work a day without re-entering the
     * password, short enough that a token copied off a machine expires on its own — there
     * is no revocation list here, and with a stateless token there cannot be one: a token
     * is valid until it expires or the signing key changes.
     */
    private long tokenTtlMinutes = 720;

    /**
     * Consecutive failed logins before the login endpoint stops answering for
     * {@link #lockoutSeconds}. Guessing a password over HTTP is otherwise limited only by
     * how fast BCrypt runs, which is thousands of attempts a day — enough to matter for a
     * password a human chose.
     *
     * <p>Zero or less disables the lockout.
     */
    private int maxFailedAttempts = 5;

    /** How long the lockout lasts. Cleared early by a successful login. */
    private long lockoutSeconds = 300;
}
