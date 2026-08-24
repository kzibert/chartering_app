package com.chartering.audit;

import com.chartering.model.DataChange;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * What a logged change can be put back, and how.
 *
 * <p>Reverting is deliberately narrow: <b>one field of one update, set back to the value the
 * log says it had.</b> Everything wider was considered and left out, and the reasons are
 * worth writing down because they look like omissions.
 *
 * <p><b>A create is not reverted</b>, because the revert of a create is a delete, and a
 * delete here cascades — {@code people.company_id} and {@code contacts.company_id} are both
 * {@code ON DELETE CASCADE}. Undoing "company created" would silently take everyone since
 * filed under it. Deleting is a thing the user does on the record's own screen, where the
 * button says what it will destroy.
 *
 * <p><b>A delete is not reverted</b>, though the snapshot holds everything needed to write
 * the row back. Restoring it would either reuse the old id, which the identity sequence has
 * moved past and other rows may since have pointed at, or take a new one, which leaves every
 * reference to the old id still dangling. That is a data-repair job with the snapshot in
 * front of you, not a button.
 *
 * <p>What is left — putting a field back — is exactly the case that comes up: somebody
 * flagged the wrong address not-working, or overwrote a greeting. It is safe because the row
 * still exists, the target type is checked, and the revert is itself logged.
 */
public final class RevertSupport {

    /**
     * Types the log's text can be turned back into.
     *
     * <p>A whitelist rather than a general converter. Everything here round-trips exactly
     * through {@code String.valueOf} and back; a type that does not is a type where a revert
     * would write something subtly unlike what was there before, which is worse than
     * refusing.
     */
    private static final java.util.Map<Class<?>, Function<String, Object>> CONVERTERS =
            java.util.Map.ofEntries(
                    java.util.Map.entry(String.class, s -> s),
                    java.util.Map.entry(Boolean.class, Boolean::valueOf),
                    java.util.Map.entry(boolean.class, Boolean::valueOf),
                    java.util.Map.entry(Long.class, Long::valueOf),
                    java.util.Map.entry(long.class, Long::valueOf),
                    java.util.Map.entry(Integer.class, Integer::valueOf),
                    java.util.Map.entry(int.class, Integer::valueOf),
                    java.util.Map.entry(Double.class, Double::valueOf),
                    java.util.Map.entry(double.class, Double::valueOf),
                    java.util.Map.entry(BigDecimal.class, BigDecimal::new),
                    java.util.Map.entry(UUID.class, UUID::fromString),
                    java.util.Map.entry(OffsetDateTime.class, OffsetDateTime::parse),
                    java.util.Map.entry(LocalDate.class, LocalDate::parse),
                    java.util.Map.entry(LocalDateTime.class, LocalDateTime::parse),
                    java.util.Map.entry(Instant.class, Instant::parse));

    /**
     * Fields that exist but must never be moved by a revert.
     *
     * <p>Not because the conversion would fail — {@code id} is a Long like any other — but
     * because the field is the row's identity or its provenance. Putting an id "back" would
     * point the row at a different record entirely.
     */
    private static final Set<String> NEVER_REVERTIBLE = Set.of("id", "legacyId", "createdAt", "updatedAt");

    private RevertSupport() {
    }

    /**
     * Why this change cannot be reverted, or null if it can.
     *
     * <p>A reason string rather than a bare boolean because the UI shows it: a disabled
     * button that will not say why is a button the user clicks again tomorrow.
     */
    public static String blockedReason(DataChange change) {
        if (!ChangeRow.UPDATE.equals(change.getOperation()) || change.getFieldName() == null) {
            return "Only a change to a single field can be put back. A created or deleted "
                    + "record has to be restored by hand — the values are in this entry.";
        }
        if (NEVER_REVERTIBLE.contains(change.getFieldName())) {
            return "This field identifies the record rather than describing it, so it is "
                    + "never moved automatically.";
        }
        Class<?> entityClass = AuditedEntities.classOf(change.getEntityType());
        if (entityClass == null) {
            return "This kind of record is no longer one the application knows about.";
        }
        Optional<Field> field = findField(entityClass, change.getFieldName());
        if (field.isEmpty()) {
            return "The field \"" + change.getFieldName() + "\" no longer exists on a "
                    + change.getEntityType() + ".";
        }
        Class<?> type = field.get().getType();
        if (change.getOldValue() != null && !CONVERTERS.containsKey(type) && !isEntity(type)) {
            return "The previous value cannot be read back into a " + type.getSimpleName() + ".";
        }
        return null;
    }

    /** The field to move, once {@link #blockedReason} has said it is safe to. */
    public static Field fieldOf(String entityType, String fieldName) {
        Class<?> entityClass = AuditedEntities.classOf(entityType);
        return entityClass == null ? null : findField(entityClass, fieldName).orElse(null);
    }

    /**
     * The stored text as the field's own type.
     *
     * <p>An association is handed back as the id it names, for the caller to load — this
     * class has no {@code EntityManager} and should not: turning text into a value and
     * fetching a row are different jobs, and only the second can fail for reasons that are
     * nothing to do with the log.
     */
    public static Object convert(String stored, Class<?> type) {
        if (stored == null) return null;
        Function<String, Object> converter = CONVERTERS.get(type);
        if (converter != null) return converter.apply(stored);
        if (isEntity(type)) return Long.valueOf(stored);
        throw new IllegalArgumentException(
                "Cannot read \"" + stored + "\" back into a " + type.getSimpleName() + ".");
    }

    /** True when the field points at another record rather than holding a value. */
    public static boolean isEntity(Class<?> type) {
        return type.isAnnotationPresent(jakarta.persistence.Entity.class);
    }

    /**
     * The id of a referenced record, without loading it if it is still a proxy.
     *
     * <p>Read off the object rather than looked up through {@link AuditedEntities}, because
     * the target of an association need not be audited itself — a vessel's flag or a
     * company's port is a reference to a table with no history of its own, and asking the
     * audit registry about it would come back empty and be mistaken for "no value".
     */
    public static String idOf(Object entity) {
        if (entity == null) return null;
        Object id = entity instanceof org.hibernate.proxy.HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getIdentifier()
                : invokeGetId(entity);
        return id == null ? null : String.valueOf(id);
    }

    private static Object invokeGetId(Object entity) {
        try {
            return entity.getClass().getMethod("getId").invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Optional<Field> findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return Optional.of(c.getDeclaredField(name));
            } catch (NoSuchFieldException ignored) {
                // Keep walking up; a mapped superclass would hold it.
            }
        }
        return Optional.empty();
    }
}
