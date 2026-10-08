package com.chartering.tenancy;

import com.chartering.security.AuthenticatedUser;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Which desk - and, where it matters, which person - the code on this thread is working for.
 *
 * <p>Two sources, asked in order. Work started without a request - a timer, a sweep, a sync -
 * names its desk explicitly with {@link #runAs}; anything else is a request, and the desk is
 * the logged-in account's. There is no third source and no default. Nothing bound means no
 * desk, and every tenant-scoped query then matches nothing and every insert fails, which is
 * the failure to want: a forgotten {@code runAs} shows up as an empty screen or an exception
 * in a log, never as one desk's rows written under another's name.
 *
 * <p>The person matters only for what is personal - a mailbox, its folders and rules, the
 * replies sent from it. A mailbox sync runs as the account whose mailbox it reads
 * ({@link #runAs(Long, Long, Runnable)}); a desk-wide sweep binds no person at all, and sees
 * the whole desk.
 *
 * <p>The explicit binding is a {@code ThreadLocal}, which {@link com.chartering.audit.ChangeContext}
 * warns against for good reason - a pooled thread keeps what the last task left in it. Here
 * every binding is made and undone by {@link #runAs} itself in a {@code finally}, so nothing
 * outlives the call that set it, and a nested call restores the outer binding on the way out.
 *
 * <p>Bind <em>before</em> the transaction opens. Hibernate asks for the tenant when a session
 * starts and keeps the answer for the session's life, so a {@code runAs} inside a transaction
 * already open for another desk changes nothing.
 */
public final class TenantContext {

    private record Binding(Long tenantId, Long userId) {
    }

    private static final ThreadLocal<Binding> BOUND = new ThreadLocal<>();

    private TenantContext() {
    }

    /** The desk on this thread: an explicit binding, else the logged-in account's. */
    public static Optional<Long> current() {
        Binding bound = BOUND.get();
        if (bound != null) return Optional.of(bound.tenantId());
        return AuthenticatedUser.current().map(AuthenticatedUser::tenantId);
    }

    /** The desk, where the code path makes no sense without one. */
    public static Long require() {
        return current().orElseThrow(() -> new IllegalStateException(
                "No tenant on this thread. Work started outside a request must run inside TenantContext.runAs."));
    }

    /**
     * The person on this thread: one bound with the desk, else the logged-in account. Empty for
     * desk-wide work, which is what lets a sweep read every mailbox on the desk.
     */
    public static Optional<Long> currentUser() {
        Binding bound = BOUND.get();
        if (bound != null && bound.userId() != null) return Optional.of(bound.userId());
        return AuthenticatedUser.current().map(AuthenticatedUser::userId);
    }

    /** The person, where the code path makes no sense without one (writing a personal row). */
    public static Long requireUser() {
        return currentUser().orElseThrow(() -> new IllegalStateException(
                "No user on this thread. Personal rows are written inside a request or TenantContext.runAs(tenant, user, …)."));
    }

    public static void runAs(Long tenantId, Runnable work) {
        runAs(tenantId, null, work);
    }

    /** As {@code userId} on {@code tenantId}: how a sync of one person's mailbox runs. */
    public static void runAs(Long tenantId, Long userId, Runnable work) {
        callAs(tenantId, userId, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T callAs(Long tenantId, Supplier<T> work) {
        return callAs(tenantId, null, work);
    }

    public static <T> T callAs(Long tenantId, Long userId, Supplier<T> work) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        Binding previous = BOUND.get();
        BOUND.set(new Binding(tenantId, userId));
        try {
            return work.get();
        } finally {
            if (previous == null) BOUND.remove();
            else BOUND.set(previous);
        }
    }

    /**
     * {@code work}, ready to hand to another thread with this thread's desk, person and login.
     *
     * <p>A request that starts a background run ("Sync now", sending a circular) is the only
     * place that run can learn whose it is: the worker thread has no request and no security
     * context of its own. The login travels too, so the change log still says who pressed the
     * button.
     */
    public static Runnable carry(Runnable work) {
        Long tenantId = require();
        Long userId = currentUser().orElse(null);
        SecurityContext security = SecurityContextHolder.getContext();
        return () -> {
            SecurityContext before = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(security);
            try {
                runAs(tenantId, userId, work);
            } finally {
                SecurityContextHolder.setContext(before);
            }
        };
    }

    /** {@link #carry(Runnable)} for work that returns a value. */
    public static <T> Callable<T> carry(Callable<T> work) {
        Long tenantId = require();
        Long userId = currentUser().orElse(null);
        SecurityContext security = SecurityContextHolder.getContext();
        return () -> {
            SecurityContext before = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(security);
            try {
                return callAs(tenantId, userId, () -> {
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
