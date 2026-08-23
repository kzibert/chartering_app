package com.chartering.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Turns a valid {@code Authorization: Bearer <token>} header into an authenticated request.
 *
 * <p>Nothing here rejects anything. A request with no header, or with a bad token, simply
 * passes through unauthenticated and is refused further down by the authorization rules in
 * {@link com.chartering.config.SecurityConfig} — which is what lets the same filter sit in
 * front of the public endpoints (login, health) without special-casing them.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(PREFIX.length()).trim();
            jwtService.subjectOf(token).ifPresent(username -> {
                // No roles: every authenticated caller here is the same one person, and an
                // authority that is granted to everybody grants nothing. The single ROLE_USER
                // is present only because an empty authority list reads as "anonymous" to
                // parts of Spring Security.
                var auth = new UsernamePasswordAuthenticationToken(
                        username, null, List.of(new SimpleUserAuthority()));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }

    /** The one authority in this application. See the note above. */
    static final class SimpleUserAuthority implements org.springframework.security.core.GrantedAuthority {
        @Override
        public String getAuthority() {
            return "ROLE_USER";
        }
    }
}
