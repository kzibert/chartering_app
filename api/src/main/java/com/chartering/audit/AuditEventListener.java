package com.chartering.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns Hibernate's flush events into change-log rows.
 *
 * <p>Hibernate hands these events the entity's state as an array parallel to
 * {@code persister.getPropertyNames()}, and for an update it hands over the old array as
 * well. That is the whole reason the log lives here rather than in the services: the before
 * value is sitting right there, already loaded, with no second query and nothing for a
 * service to remember to do. A change made from anywhere — a form, the importer, a one-off
 * fixup — is logged the same way, because they all end in a flush.
 *
 * <p>The listener decides <em>what</em> changed; {@link DataChangeWriter} decides how it is
 * stored, and {@link AuditedEntities} decides which entities are in scope at all.
 */
@Slf4j
@RequiredArgsConstructor
public class AuditEventListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private final DataChangeWriter writer;
    private final ObjectMapper objectMapper;

    /**
     * False, so these fire inside the flush and inside the transaction. Post-commit handling
     * would put the log outside the transaction it describes — see {@link DataChangeWriter}
     * for why that is the wrong side of the line.
     */
    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        capture(event.getEntity(), event.getId(), () -> {
            String snapshot = snapshot(event.getPersister(), event.getState(), event.getId());
            return List.of(row(event.getEntity(), event.getId(),
                    ChangeRow.CREATE, null, null, snapshot));
        });
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        capture(event.getEntity(), event.getId(), () -> {
            // Everything the row held, in one place. This is what makes an accidental delete
            // recoverable at all — a per-field log of a delete would be a column of values
            // going to null, which says what was lost without saying what it was.
            String snapshot = snapshot(event.getPersister(), event.getDeletedState(), event.getId());
            return List.of(row(event.getEntity(), event.getId(),
                    ChangeRow.DELETE, null, snapshot, null));
        });
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        capture(event.getEntity(), event.getId(), () -> {
            Object[] oldState = event.getOldState();
            Object[] newState = event.getState();
            if (oldState == null) {
                // Hibernate did not load the previous state — a detached update, or a
                // stateless session. There is a new value but no old one, so a per-field
                // diff would claim every field changed from nothing. A snapshot of where it
                // ended up is the honest record.
                return List.of(row(event.getEntity(), event.getId(), ChangeRow.UPDATE, null,
                        null, snapshot(event.getPersister(), newState, event.getId())));
            }

            String[] names = event.getPersister().getPropertyNames();
            Type[] types = event.getPersister().getPropertyTypes();
            List<ChangeRow> rows = new ArrayList<>();

            for (int i = 0; i < names.length; i++) {
                if (skip(names[i], types[i])) continue;
                String before = render(types[i], oldState[i]);
                String after = render(types[i], newState[i]);
                // Compared as rendered text rather than by Hibernate's dirty flags. The
                // flags mark a property Hibernate intends to write, which is not the same
                // as a value the user changed: a BigDecimal rescaled or a string handed
                // back identical still counts as dirty, and a log full of "1.00 -> 1.00"
                // is a log that gets ignored.
                if (!Objects.equals(before, after)) {
                    rows.add(row(event.getEntity(), event.getId(),
                            ChangeRow.UPDATE, names[i], before, after));
                }
            }
            return rows;
        });
    }

    /**
     * Runs the body for an audited entity and writes whatever it produced.
     *
     * <p>Every failure inside is swallowed and logged. The log describes work that has
     * already happened and mostly already been written; letting a defect in it throw would
     * roll back a save the user believes succeeded, which is a far worse outcome than a
     * missing history row.
     */
    private void capture(Object entity, Object id, ChangeSupplier body) {
        if (!(id instanceof Long entityId) || AuditedEntities.typeOf(entity) == null) return;
        try {
            List<ChangeRow> rows = body.get();
            if (!rows.isEmpty()) writer.write(rows);
        } catch (RuntimeException e) {
            log.error("Could not build change-log rows for {} {}; the change itself was not affected",
                    entity.getClass().getSimpleName(), entityId, e);
        }
    }

    @FunctionalInterface
    private interface ChangeSupplier {
        List<ChangeRow> get();
    }

    private ChangeRow row(Object entity, Object id, String operation,
                          String field, String oldValue, String newValue) {
        return new ChangeRow(
                AuditedEntities.typeOf(entity),
                (Long) id,
                AuditedEntities.labelOf(entity),
                operation, field, oldValue, newValue);
    }

    /** Every readable property of a row, as JSON, for a create or a delete. */
    private String snapshot(EntityPersister persister, Object[] state, Object id) {
        Map<String, String> values = new LinkedHashMap<>();
        // The identifier is not among getPropertyNames(), and a snapshot without it could
        // not be used to put a deleted row back where it was.
        values.put("id", String.valueOf(id));

        String[] names = persister.getPropertyNames();
        Type[] types = persister.getPropertyTypes();
        for (int i = 0; i < names.length; i++) {
            if (skip(names[i], types[i])) continue;
            String rendered = render(types[i], state == null ? null : state[i]);
            if (rendered != null) values.put(names[i], rendered);
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            // A map of strings does not fail to serialise, but the checked exception has to
            // go somewhere and losing the snapshot is better than losing the save.
            log.error("Could not serialise a change-log snapshot", e);
            return null;
        }
    }

    /**
     * Properties the log has no use for.
     *
     * <p>Collections are skipped because their state here is a {@code PersistentCollection},
     * not a value — a company's ports would render as an object identity, which is worse
     * than absent. They are also usually their own join table, where the interesting change
     * is a row appearing rather than a field moving.
     */
    private static boolean skip(String name, Type type) {
        return type.isCollectionType() || AuditedEntities.IGNORED_FIELDS.contains(name);
    }

    /**
     * One property value as text.
     *
     * <p>An association renders as the id it points at, never as the entity: the target has
     * its own history under its own type, and inlining it would duplicate that here and
     * force a lazy proxy to load during a flush to do it.
     */
    private static String render(Type type, Object value) {
        if (value == null) return null;
        if (type.isEntityType()) {
            Object id = identifierOf(value);
            return id == null ? null : String.valueOf(id);
        }
        return String.valueOf(value);
    }

    /**
     * The id of an associated entity, without initialising it.
     *
     * <p>A proxy is asked for the identifier it already carries; anything else is asked for
     * its {@code getId()}. Every entity in this application has one, so the reflective call
     * is a formality — but it fails soft rather than throwing, because a missing association
     * id in the log is a cosmetic loss and an exception mid-flush is not.
     */
    private static Object identifierOf(Object value) {
        if (value instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getIdentifier();
        }
        try {
            return value.getClass().getMethod("getId").invoke(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
