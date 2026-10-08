package com.chartering.tenancy;

import com.chartering.security.AuthenticatedUser;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Which desk the code on this thread is working for.
 *
 * <p>Two sources, asked in order. Work started without a request - a timer, a sweep, a sync -
 * names its desk explicitly with {@link #runAs}; anything else is a request, and the desk is
 * the logged-in account's. There is no third source and no default. Nothing bound means no
 * desk, and every tenant-scoped query then matches nothing and every insert fails, which is
 * the failure to want: a forgotten {@code runAs} shows up as an empty screen or an exception
 * in a log, never as one desk's rows written under another's name.
 *
 * <p>The explicit binding is a {@code ThreadLocal}, which {@link com.chartering.audit.ChangeContext}
 * warns against for good reason - a pooled thread keeps what the last task left in it. Here
 * every binding is made and undone by {@link #runAs} itself in a {@code finally}, so nothing
 * outlives the call that set it, and a nested call restores the outer desk on the way out.
 *
 * <p>Bind the desk <em>before</em> the transaction opens. Hibernate asks for the tenant when a
 * session starts and keeps the answer for the session's life, so a {@code runAs} inside a
 * transaction already open for another desk changes nothing.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> BOUND = new ThreadLocal<>();

    private TenantContext() {
    }

    /** The desk on this thread: an explicit binding, else the logged-in account's. */
    public static Optional<Long> current() {
        Long bound = BOUND.get();
        if (bound != null) return Optional.of(bound);
        return AuthenticatedUser.current().map(AuthenticatedUser::tenantId);
    }

    /** The desk, where the code path makes no sense without one. */
    public static Long require() {
        return current().orElseThrow(() -> new IllegalStateException(
                "No tenant on this thread. Work started outside a request must run inside TenantContext.runAs."));
    }

    public static void runAs(Long tenantId, Runnable work) {
        callAs(tenantId, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T callAs(Long tenantId, Supplier<T> work) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        Long previous = BOUND.get();
        BOUND.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) BOUND.remove();
            else BOUND.set(previous);
        }
    }

    /**
     * {@code work}, ready to hand to another thread with this thread's desk and login.
     *
     * <p>A request that starts a background run ("Sync now", "Parse now", sending a circular)
     * is the only place that run can learn whose it is: the worker thread has no request and
     * no security context of its own. The login travels too, so the change log still says who
     * pressed the button.
     */
    public static Runnable carry(Runnable work) {
        Long tenantId = require();
        SecurityContext security = SecurityContextHolder.getContext();
        return () -> {
            SecurityContext before = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(security);
            try {
                runAs(tenantId, work);
            } finally {
                SecurityContextHolder.setContext(before);
            }
        };
    }

    /** {@link #carry(Runnable)} for work that returns a value. */
    public static <T> Callable<T> carry(Callable<T> work) {
        Long tenantId = require();
        SecurityContext security = SecurityContextHolder.getContext();
        return () -> {
            SecurityContext before = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(security);
            try {
                return callAs(tenantId, () -> {
                    try {
                        return work.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            } finally {
                SecurityContextHolder.setContext(before);
            }
        };
    }
}
