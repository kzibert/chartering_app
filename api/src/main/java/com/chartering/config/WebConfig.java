package com.chartering.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS, as a {@link CorsConfigurationSource} bean rather than as {@code addCorsMappings}.
 *
 * <p>That distinction matters now that there is a security filter chain. The MVC-level
 * mapping is applied by the handler mapping, which sits <em>behind</em> the filters — so a
 * preflight {@code OPTIONS}, which carries no {@code Authorization} header, would be
 * rejected as unauthenticated before it ever reached the code that would have allowed it.
 * As a bean, Spring Security's own CORS filter picks this up and answers preflights in
 * front of the authorization rules.
 *
 * <p>In normal use nothing needs this at all: the UI's nginx proxies {@code /api} to the
 * api, so the browser is making a same-origin request. It exists for the split case — a
 * Vite dev server on another port, or a UI deployed on a different host from the API — and
 * {@code CORS_ORIGINS} is what narrows it there.
 */
@Configuration
public class WebConfig {

    /**
     * Comma-separated origins allowed to call the API from a browser, or {@code *} for any.
     *
     * <p>{@code *} is not the hole it would be on a cookie-authenticated app: the token
     * travels in a header the browser will not attach on its own, so a page on another
     * origin can reach these endpoints and get a 401 for its trouble. Naming the real origin
     * is still better — set {@code CORS_ORIGINS=https://your-ui.onrender.com} once the UI
     * has an address.
     */
    @Value("${chartering.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Credentials stay off, which is what keeps "*" legal above — the two are mutually
        // exclusive in the CORS spec, and this app has no cookies to send anyway.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
