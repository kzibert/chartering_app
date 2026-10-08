package com.chartering.security;

import com.chartering.model.UserRole;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Who is calling: the principal {@link JwtAuthFilter} puts on every authenticated request.
 *
 * <p>An {@link AuthenticatedPrincipal}, so {@code Authentication.getName()} is still the
 * username - the change log, the reply records and the analysis samples were all written
 * against that, and keep being written the same way.
 *
 * <p>Built from the database row on each request rather than from the token's claims alone,
 * so a role changed or an account disabled takes effect on the next call, not on the next
 * login.
 */
public record AuthenticatedUser(
        Long userId,
        Long tenantId,
        String username,
        UserRole role,
        boolean mustChangePassword) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return username;
    }

    /** The caller on this thread, if a request put one there. */
    public static Optional<AuthenticatedUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** The caller, where the code path cannot run without one. */
    public static AuthenticatedUser require() {
        return current().orElseThrow(() -> new IllegalStateException("No authenticated user on this thread"));
    }
}
