package com.chartering.security;

import com.chartering.config.ParserProperties;
import com.chartering.service.parser.ParserRoute;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the caller's parser choice ({@link ParserRoute}) for the length of an Intake request.
 *
 * <p>The browser sends {@value #PROVIDER_HEADER}{@code : CLOUD} and its own key in
 * {@value #KEY_HEADER} on {@code /api/v1/intake/**} and nowhere else, so the key does not travel
 * with every request the app makes. Anything else — no header, a blank key, a deployment that
 * does not offer the cloud model ({@code PARSER_CLOUD_ENABLED}) — is the local route, and
 * {@code ParserAvailability} decides whether that exists here.
 *
 * <p>The key is read and bound, never logged: a malformed one is ignored rather than quoted in
 * an error, because the only useful thing to say about it is "the provider refused it", and the
 * provider says that itself.
 */
@Component
@RequiredArgsConstructor
public class ParserRouteFilter extends OncePerRequestFilter {

    public static final String PROVIDER_HEADER = "X-Parser-Provider";
    public static final String KEY_HEADER = "X-Parser-Key";

    /** A Groq key is 56 characters; this only refuses something that is plainly not a key. */
    private static final int MAX_KEY_LENGTH = 512;

    private final ParserProperties props;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/intake");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        ParserRoute route = routeOf(request.getHeader(PROVIDER_HEADER), request.getHeader(KEY_HEADER));
        try {
            ParserRoute.callWith(route, () -> {
                try {
                    chain.doFilter(request, response);
                    return null;
                } catch (IOException | ServletException e) {
                    throw new Wrapped(e);
                }
            });
        } catch (Wrapped w) {
            if (w.getCause() instanceof IOException io) throw io;
            throw (ServletException) w.getCause();
        }
    }

    /** Package-private so the rule can be tested without a servlet. */
    ParserRoute routeOf(String provider, String key) {
        if (!props.isCloudEnabled() || !"CLOUD".equalsIgnoreCase(provider == null ? "" : provider.strip())) {
            return ParserRoute.LOCAL;
        }
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH
                || !key.strip().chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            return ParserRoute.LOCAL;
        }
        return ParserRoute.cloud(key);
    }

    private static final class Wrapped extends RuntimeException {
        Wrapped(Exception cause) {
            super(cause);
        }
    }
}
