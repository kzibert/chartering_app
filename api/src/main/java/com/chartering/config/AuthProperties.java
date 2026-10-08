package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The first account, the key tokens are signed with, and the login policy.
 *
 * <p><b>Accounts live in the {@code users} table</b> (V30); each person logs in as
 * themselves and belongs to one desk. The username and password below no longer decide who
 * may log in. They seed the very first account - a platform administrator on the default desk
 * - when the table is empty ({@code UserBootstrap}), which is how an installation that used to
 * have one environment credential upgrades without anybody being locked out: the same name and
 * password keep working, now as a row. After that, changing them here changes nothing, unless
 * {@link #resetPassword} asks for exactly that.
 */
@Component
@ConfigurationProperties(prefix = "chartering.auth")
@Data
public class AuthProperties {

    /** The first account's login name. Not an email address unless you make it one. */
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
     * password, short enough that a token copied off a machine expires on its own. There is
     * no list of issued tokens; what revokes them early is the account's
     * {@code token_version}, bumped by a disable, a password change or reset and a role
     * change - all of one account's tokens at once, which is the granularity that matters.
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

    /**
     * The way back in when the only administrator has lost their password. When true, the
     * account named {@link #username} is given the configured password at startup, unlocked
     * and re-enabled, and every token it held is revoked. Meant to be switched on for one
     * restart and off again - left on, the environment would quietly own that password again.
     */
    private boolean resetPassword;
}
