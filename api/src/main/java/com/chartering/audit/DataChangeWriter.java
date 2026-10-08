package com.chartering.audit;

import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Puts change rows in the table.
 *
 * <p><b>Plain JDBC, on the transaction's own connection, during the flush that caused the
 * change.</b> Every part of that is deliberate.
 *
 * <p>JDBC rather than the {@code EntityManager}, because this runs inside a Hibernate flush:
 * persisting an entity there appends to the action queue that is currently being drained,
 * which is how a flush turns into a {@code ConcurrentModificationException}. Raw JDBC never
 * touches the persistence context, so the flush does not notice it happened.
 *
 * <p>The transaction's connection rather than a new one, because a change log that can
 * survive its own rollback is worse than no log: it would record edits that never happened,
 * and the rows it invented would be indistinguishable from the real ones. Sharing the
 * connection means the log commits with the data or dies with it.
 *
 * <p>During the flush rather than in a commit hook, because Spring's {@code
 * JpaTransactionManager} triggers before-commit synchronizations <em>before</em> the session
 * flushes. A hook there would run before the updates it was meant to describe existed, and
 * would quietly log nothing at all for a service that only mutates managed entities — which
 * is most of them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataChangeWriter {

    private static final String INSERT = """
            insert into data_changes
              (change_set, entity_type, entity_id, entity_label, operation,
               field_name, old_value, new_value, changed_at, changed_by, context, tenant_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    void write(List<ChangeRow> rows) {
        if (rows.isEmpty()) return;

        UUID changeSet = ChangeContext.changeSet();
        OffsetDateTime at = ChangeContext.changedAt();
        String by = ChangeContext.changedBy();
        String context = ChangeContext.context();

        // The desk the change was made on, which is the desk of the session that flushed it:
        // Hibernate binds a session to one desk, so the rows being described are that desk's.
        // Checked before the insert rather than left to NOT NULL, because a failed statement
        // aborts the whole Postgres transaction - the edit would be lost with its history.
        Long tenantId = TenantContext.current().orElse(null);
        if (tenantId == null) {
            log.error("Not writing {} change-log row(s): no tenant on this thread", rows.size());
            return;
        }

        try {
            jdbcTemplate.batchUpdate(INSERT, rows, rows.size(), (ps, row) -> {
                ps.setObject(1, changeSet);
                ps.setString(2, row.entityType());
                ps.setLong(3, row.entityId());
                ps.setString(4, row.entityLabel());
                ps.setString(5, row.operation());
                ps.setString(6, row.fieldName());
                setText(ps, 7, row.oldValue());
                setText(ps, 8, row.newValue());
                ps.setObject(9, at);
                ps.setString(10, by);
                ps.setString(11, context);
                ps.setLong(12, tenantId);
            });
        } catch (RuntimeException e) {
            // The log is a record of the work, not the work. A save that succeeded must not
            // be rolled back because its history row would not go in — that would turn an
            // auditing problem into a data-entry problem, and the user would be left with a
            // failed edit and no way to tell why. It is logged loudly instead.
            log.error("Could not write {} change-log row(s); the change itself was not affected",
                    rows.size(), e);
        }
    }

    /**
     * Sets a possibly-null text parameter.
     *
     * <p>{@code setString(i, null)} is fine on Postgres but leaves the driver to guess the
     * type of an untyped null, which it does by looking at the statement — and gets wrong in
     * a batch where earlier rows had a value. Saying VARCHAR outright avoids the question.
     */
    private static void setText(java.sql.PreparedStatement ps, int index, String value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
