package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One person's login.
 *
 * <p>Named {@code AppUser} rather than {@code User} only to stay clear of Spring Security's
 * own {@code User} class, which half the imports in the security package would otherwise
 * collide with. The table is {@code users}.
 *
 * <p>Not tenant-scoped and not audited. Not scoped because the login reads it before anybody
 * is known; the admin endpoints filter by desk themselves. Not audited because the row carries
 * the lockout counters, and a change log that records every mistyped password is a change log
 * nobody reads.
 *
 * <p>An account is disabled rather than deleted. Its name is on years of change-log rows and
 * replies, and those must keep meaning somebody.
 */
@Getter
@Setter
@Entity
@Table(name = "users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    /** The login name; unique across the installation, compared case-insensitively. */
    @Column(nullable = false, length = 150)
    private String username;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Set on every password an administrator chose - a new account, a reset - so the password
     * somebody else has seen is used once, to choose one nobody else has. Enforced on the
     * server: such a session can change its password and nothing else.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    /** Carried in every token; bumping it revokes them all. See V30. */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by", length = 150)
    private String createdBy;

    /** Every outstanding token stops working. */
    public void revokeTokens() {
        tokenVersion++;
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now());
    }
}
