package com.chartering.service;

import com.chartering.audit.AuditedEntities;
import com.chartering.audit.ChangeContext;
import com.chartering.audit.RevertSupport;
import com.chartering.dto.DataChangeResponse;
import com.chartering.dto.PageResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.DataChange;
import com.chartering.repository.DataChangeRepository;
import com.chartering.specification.DataChangeSpecification;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reading the change log, and putting one entry of it back.
 *
 * <p>Nothing here writes to the log. Rows arrive from {@code audit/AuditEventListener}
 * during the flush that caused them, which is why a revert made through this service shows
 * up in the log as an ordinary change with the reverting user's name on it — it went through
 * the same save as any other edit, and pretending otherwise would leave a history with a
 * silent step in it.
 */
@Service
@RequiredArgsConstructor
public class DataChangeService {

    private final DataChangeRepository repository;
    private final EntityManager entityManager;
    private final DtoMapper mapper;

    public record ChangeFilter(
            String entityType,
            Long entityId,
            String operation,
            String field,
            String changedBy,
            UUID changeSet,
            OffsetDateTime from,
            OffsetDateTime until,
            String text) {
    }

    @Transactional(readOnly = true)
    public PageResponse<DataChangeResponse> search(ChangeFilter filter, Pageable pageable) {
        Specification<DataChange> spec = Specification.allOf(
                DataChangeSpecification.entityTypeEquals(filter.entityType()),
                DataChangeSpecification.entityIdEquals(filter.entityId()),
                DataChangeSpecification.operationEquals(filter.operation()),
                DataChangeSpecification.fieldEquals(filter.field()),
                DataChangeSpecification.changedByEquals(filter.changedBy()),
                DataChangeSpecification.changeSetEquals(filter.changeSet()),
                DataChangeSpecification.changedFrom(filter.from()),
                DataChangeSpecification.changedUntil(filter.until()),
                DataChangeSpecification.textContains(filter.text()));
        return PageResponse.from(repository.findAll(spec, pageable).map(mapper::toDataChangeResponse));
    }

    @Transactional(readOnly = true)
    public DataChangeResponse get(Long id) {
        return mapper.toDataChangeResponse(find(id));
    }

    /** The type names the log can carry, for the filter dropdown. */
    public List<String> entityTypes() {
        return AuditedEntities.types();
    }

    @Transactional(readOnly = true)
    public List<String> users() {
        return repository.findDistinctChangedBy();
    }

    /**
     * Put one field back to the value this entry says it had.
     *
     * <p>Refuses when the field has moved on since — when the value in the database is no
     * longer the {@code newValue} this entry recorded. That is not caution for its own sake:
     * silently overwriting a later change would destroy an edit nobody has looked at, and it
     * would do it while the user believes they are undoing something else. The way through
     * is to revert the later change first, which composes to the same place and shows each
     * step on the way.
     *
     * <p>The write goes through the entity itself rather than an UPDATE, so validation,
     * timestamps and the change log all behave exactly as they do for a hand edit.
     */
    @Transactional
    public DataChangeResponse revert(Long id) {
        DataChange change = find(id);

        String blocked = RevertSupport.blockedReason(change);
        if (blocked != null) {
            throw new IllegalArgumentException(blocked);
        }

        Class<?> entityClass = AuditedEntities.classOf(change.getEntityType());
        Object entity = entityManager.find(entityClass, change.getEntityId());
        if (entity == null) {
            throw new IllegalArgumentException(
                    "That " + change.getEntityType() + " no longer exists, so there is nothing "
                            + "to put back. It was deleted after this change was made.");
        }

        Field field = RevertSupport.fieldOf(change.getEntityType(), change.getFieldName());
        field.setAccessible(true);

        String current = currentValueOf(entity, field);
        if (!Objects.equals(current, change.getNewValue())) {
            throw new IllegalArgumentException(
                    "\"" + change.getFieldName() + "\" has changed again since — it is now "
                            + describe(current) + ", not " + describe(change.getNewValue())
                            + ". Put the newer change back first.");
        }

        Object value = RevertSupport.convert(change.getOldValue(), field.getType());
        if (value != null && RevertSupport.isEntity(field.getType())) {
            // The converter hands back the id; the row itself has to be fetched, and may be
            // gone even though the record holding the reference is not.
            value = entityManager.find(field.getType(), value);
            if (value == null) {
                throw new IllegalArgumentException(
                        "The " + field.getType().getSimpleName().toLowerCase()
                                + " this used to point at no longer exists.");
            }
        }

        try {
            field.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not set " + change.getFieldName(), e);
        }

        // Says so in the log rather than leaving the revert looking like somebody typing the
        // old value back in by coincidence.
        ChangeContext.describe("Undo of change #" + change.getId());
        entityManager.flush();
        return mapper.toDataChangeResponse(change);
    }

    private String currentValueOf(Object entity, Field field) {
        try {
            Object value = field.get(entity);
            if (value == null) return null;
            if (RevertSupport.isEntity(field.getType())) {
                return RevertSupport.idOf(value);
            }
            return String.valueOf(value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read " + field.getName(), e);
        }
    }

    /** A value as it reads in a refusal message. */
    private static String describe(String value) {
        return value == null ? "empty" : "\"" + value + "\"";
    }

    private DataChange find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DataChange", id));
    }
}
