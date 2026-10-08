package com.chartering.tenancy;

import com.chartering.model.UserRole;
import com.chartering.security.AuthenticatedUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * A logged-in person for unit tests that call services directly: what the request filter puts
 * on the thread, so desk- and owner-scoped code finds somebody there. Pair every {@link #as}
 * with {@link #clear} in an {@code @AfterEach}.
 */
public final class TestLogin {

    public static final Long USER_ID = 42L;
    public static final Long TENANT_ID = 1L;

    private TestLogin() {
    }

    public static void as(Long userId, Long tenantId) {
        AuthenticatedUser user = new AuthenticatedUser(userId, tenantId, "tester", UserRole.TENANT_ADMIN, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    public static void asDefault() {
        as(USER_ID, TENANT_ID);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
