package com.chartering.config;

import com.chartering.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything is closed except the few endpoints that have to answer before anyone is logged
 * in. The rule is deliberately {@code anyRequest().authenticated()} rather than a list of
 * protected paths: a new controller added next month is protected by default, and the way to
 * make something public is to say so here, in the one file that decides it.
 *
 * <p><b>Stateless.</b> No session, no CSRF token, no login form, no {@code JSESSIONID}. The
 * credential travels on every request as a bearer token, which is also why CSRF is switched
 * off rather than left on and worked around: a cross-site request cannot attach an
 * {@code Authorization} header, so the attack CSRF tokens exist to stop does not apply. That
 * equivalence only holds while the token is <em>not</em> in a cookie — moving it into one
 * means turning CSRF protection back on.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    /**
     * BCrypt at its default strength (10). Slow by design: the whole value of a password
     * hash is that checking one takes long enough to make guessing them in bulk impractical.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})                       // uses the CorsConfigurationSource bean in WebConfig
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        // The login itself, and only the login: /auth/me is authenticated,
                        // since answering it is the whole point of checking a token.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // CORS preflight carries no Authorization header by definition — the
                        // browser sends it precisely to ask whether the real request may go.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // What the platform polls to decide a deploy is live. Exposes
                        // {"status":"UP"} and nothing else — see application.yml.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // API documentation, not data. Turn it off entirely on a deployed
                        // instance with SWAGGER_ENABLED=false, which makes these 404 rather
                        // than needing a rule change here.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        // Spring's own error dispatch. Without this a 404 inside a protected
                        // path comes back as 401, which is a confusing thing to debug.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * No token, or a token that did not verify. The body matches
     * {@link com.chartering.exception.GlobalExceptionHandler}'s shape so the UI's single
     * axios interceptor can read it like any other error.
     */
    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, ex) ->
                write(response, HttpStatus.UNAUTHORIZED, "Not authenticated — please log in.");
    }

    /** Authenticated but not allowed. With one role and one user this should never fire. */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) ->
                write(response, HttpStatus.FORBIDDEN, "Not allowed.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String message)
            throws java.io.IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
