package com.chartering.security;

import com.chartering.model.AppUser;
import com.chartering.model.TenantStatus;
import com.chartering.model.UserRole;
import com.chartering.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a valid {@code Authorization: Bearer <token>} header into an authenticated request.
 *
 * <p>Nothing here rejects anything. A request with no header, or with a bad token, simply
 * passes through unauthenticated and is refused further down by the authorization rules in
 * {@link com.chartering.config.SecurityConfig} — which is what lets the same filter sit in
 * front of the public endpoints (login, health) without special-casing them.
 *
 * <p><b>The row is read on every request.</b> One primary-key lookup is the price of a token
 * that stops working the moment its account is disabled, its desk suspended, its password
 * reset or its role changed - all of which bump {@code token_version} or flip a flag the
 * token cannot know about. Trusting the claims alone would leave a dismissed employee working
 * for the rest of a twelve-hour token.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    /**
     * The one authority a session holding an administrator-chosen password gets. It reaches
     * {@code /auth/me} and {@code /auth/change-password} and nothing else - see SecurityConfig.
     */
    public static final String PASSWORD_CHANGE_AUTHORITY = "PASSWORD_CHANGE_REQUIRED";

    private final JwtService jwtService;
    private final AppUserRepository users;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(PREFIX.length()).trim();
            jwtService.claimsOf(token).flatMap(this::activeUser).ifPresent(user -> {
                AuthenticatedUser principal = new AuthenticatedUser(
                        user.getId(), user.getTenant().getId(), user.getUsername(),
                        user.getRole(), user.isMustChangePassword());
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null, authoritiesOf(principal));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        // Every log line of the request says whose it was; the desk is what an incident on a
        // shared installation is first narrowed by.
        AuthenticatedUser.current().ifPresent(u -> {
            MDC.put("tenant", String.valueOf(u.tenantId()));
            MDC.put("user", u.username());
        });
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("tenant");
            MDC.remove("user");
        }
    }

    private Optional<AppUser> activeUser(TokenClaims claims) {
        return users.findWithTenant(claims.userId())
                .filter(AppUser::isEnabled)
                .filter(u -> u.getTenant().getStatus() == TenantStatus.ACTIVE)
                .filter(u -> u.getTokenVersion() == claims.version())
                .filter(u -> u.getTenant().getId().equals(claims.tenantId()));
    }

    /**
     * Roles nest, so each one is granted with every role below it: a check for TENANT_ADMIN
     * then admits a platform administrator without every rule having to name both.
     */
    static List<GrantedAuthority> authoritiesOf(AuthenticatedUser user) {
        if (user.mustChangePassword()) {
            return List.of(new SimpleGrantedAuthority(PASSWORD_CHANGE_AUTHORITY));
        }
        List<GrantedAuthority> granted = new ArrayList<>();
        for (UserRole role : UserRole.values()) {
            if (user.role().atLeast(role)) {
                granted.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            }
        }
        return granted;
    }
}
