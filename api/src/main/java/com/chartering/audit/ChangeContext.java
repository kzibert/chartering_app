package com.chartering.audit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The three things every log row needs that the entity itself cannot supply: which change
 * set it belongs to, when the set happened, and why.
 *
 * <p>Held against the transaction rather than in a plain {@code ThreadLocal}. That is what
 * makes a change set mean something: every row written between {@code BEGIN} and
 * {@code COMMIT} gets the same id and the same timestamp, so a save that touched a person
 * and two of their addresses reads as one event, and so does an import of eighty contacts.
 * A ThreadLocal would do the same until the first request that reuses a pooled thread
 * without clearing it, and then it would silently staple two unrelated change sets
 * together.
 *
 * <p>Outside a transaction it falls back to a fresh id per call, which is the honest answer:
 * a write with no transaction around it is its own change set.
 */
public final class ChangeContext {

    private static final String SET_KEY = ChangeContext.class.getName() + ".changeSet";
    private static final String AT_KEY = ChangeContext.class.getName() + ".changedAt";
    private static final String CONTEXT_KEY = ChangeContext.class.getName() + ".context";

    private ChangeContext() {
    }

    /**
     * Say why the current transaction is changing things — "Import of contacts.csv".
     *
     * <p>Optional everywhere. A save from a form needs no explanation: the entity, the
     * fields and the user are the explanation. This is for the writes where that is not
     * enough, which in practice means the bulk ones.
     *
     * <p>Ignored outside a transaction rather than stored somewhere it would leak: there is
     * no set for it to belong to.
     */
    public static void describe(String reason) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            bind(CONTEXT_KEY, reason);
        }
    }

    /** The id shared by every row this transaction writes. */
    static UUID changeSet() {
        return current(SET_KEY, UUID::randomUUID);
    }

    /** One timestamp for the whole set, so its rows sort together rather than by field. */
    static OffsetDateTime changedAt() {
        return current(AT_KEY, OffsetDateTime::now);
    }

    static String context() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                ? (String) TransactionSynchronizationManager.getResource(CONTEXT_KEY)
                : null;
    }

    /**
     * The logged-in username, or null.
     *
     * <p>Null rather than "system" for a change with nobody behind it — a startup fixup, a
     * scheduled sync. Writing a name there would invent an account that does not exist and
     * make "who did this?" unanswerable for exactly the changes where it matters most.
     */
    static String changedBy() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        return name == null || name.isBlank() || "anonymousUser".equals(name) ? null : name;
    }

    @SuppressWarnings("unchecked")
    private static <T> T current(String key, java.util.function.Supplier<T> ifAbsent) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return ifAbsent.get();
        }
        T existing = (T) TransactionSynchronizationManager.getResource(key);
        if (existing != null) return existing;
        T created = ifAbsent.get();
        bind(key, created);
        return created;
    }

    /**
     * Binds a value for the life of the transaction and unbinds it at the end.
     *
     * <p>The synchronization is what keeps the next transaction on this thread from
     * inheriting the value. Spring unbinds resources it owns; these are ours.
     */
    private static void bind(String key, Object value) {
        if (TransactionSynchronizationManager.hasResource(key)) {
            TransactionSynchronizationManager.unbindResource(key);
        }
        TransactionSynchronizationManager.bindResource(key, value);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (TransactionSynchronizationManager.hasResource(key)) {
                    TransactionSynchronizationManager.unbindResource(key);
                }
            }
        });
    }
}
