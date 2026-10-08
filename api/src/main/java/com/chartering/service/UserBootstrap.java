package com.chartering.service;

import com.chartering.config.AuthProperties;
import com.chartering.model.AppUser;
import com.chartering.model.Tenant;
import com.chartering.model.UserRole;
import com.chartering.repository.AppUserRepository;
import com.chartering.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first account from the environment, and is the way back in when it is lost.
 *
 * <p>An installation that used to log in with {@code AUTH_USERNAME} / {@code AUTH_PASSWORD}
 * keeps logging in with them: on the first start with an empty {@code users} table, that
 * pair becomes a platform administrator on the default desk - the desk every existing row
 * belongs to. From then on accounts are made on the Admin screen and the environment is not
 * asked again, unless {@code AUTH_RESET_PASSWORD} says to.
 *
 * <p>Not fatal when nothing is configured, for the reason the old single-credential check was
 * not: an app that refuses to start is worse to diagnose than one that starts and says, at
 * the login screen, exactly what is missing.
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class UserBootstrap implements ApplicationRunner {

    private final AuthProperties props;
    private final PasswordEncoder passwordEncoder;
    private final AppUserRepository users;
    private final TenantRepository tenants;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = props.getUsername() == null ? "" : props.getUsername().trim();
        if (users.count() == 0) {
            String hash = configuredHash();
            if (hash == null || username.isEmpty()) {
                log.error("Auth: no account exists and none is configured. Set AUTH_USERNAME and "
                        + "AUTH_PASSWORD (or AUTH_PASSWORD_HASH) — until then every login is refused.");
                return;
            }
            AppUser first = new AppUser();
            first.setTenant(tenants.getReferenceById(Tenant.DEFAULT_ID));
            first.setUsername(username);
            first.setPasswordHash(hash);
            first.setRole(UserRole.PLATFORM_ADMIN);
            first.setCreatedBy("environment");
            users.save(first);
            log.info("Auth: created the first account '{}' (platform administrator, default desk) "
                    + "from the environment. Further accounts are made on the Admin screen.", username);
            return;
        }

        if (props.isResetPassword()) {
            reset(username);
        }
    }

    private void reset(String username) {
        String hash = configuredHash();
        if (hash == null || username.isEmpty()) {
            log.error("Auth: AUTH_RESET_PASSWORD is on but AUTH_USERNAME / AUTH_PASSWORD are not set; nothing reset.");
            return;
        }
        AppUser user = users.findByUsernameIgnoreCase(username).orElseGet(() -> {
            AppUser created = new AppUser();
            created.setTenant(tenants.getReferenceById(Tenant.DEFAULT_ID));
            created.setUsername(username);
            created.setCreatedBy("environment");
            return created;
        });
        user.setPasswordHash(hash);
        // The point of a reset is getting an administrator back in; an account demoted or
        // disabled by mistake is exactly the case it exists for.
        user.setRole(UserRole.PLATFORM_ADMIN);
        user.setEnabled(true);
        user.setMustChangePassword(false);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.revokeTokens();
        users.save(user);
        log.warn("Auth: AUTH_RESET_PASSWORD — '{}' now has the configured password and is a platform "
                + "administrator. Switch AUTH_RESET_PASSWORD off again before the next restart.", username);
    }

    /** The configured password as a BCrypt hash; a hash wins over a plaintext beside it. */
    private String configuredHash() {
        String hash = props.getPasswordHash();
        if (hash != null && !hash.isBlank()) {
            return hash.trim();
        }
        String plain = props.getPassword();
        if (plain != null && !plain.isBlank()) {
            return passwordEncoder.encode(plain);
        }
        return null;
    }
}
