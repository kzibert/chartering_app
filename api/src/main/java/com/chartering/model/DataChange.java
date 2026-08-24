package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the change log: a field that changed, or a record that appeared or vanished.
 *
 * <p>Read-only from the application's point of view. Nothing here is ever updated and
 * nothing is ever deleted by the code — the rows are written by
 * {@code audit/DataChangeWriter} straight through JDBC during the flush that caused them,
 * and this entity exists only so the history screens can query them like anything else.
 *
 * <p>Which shape a row is depends on {@link #fieldName}; see the migration for the full
 * reasoning. Briefly: a name means one field of an update, and {@code null} means the whole
 * record, with its fields as JSON in {@link #newValue} for a create and in {@link #oldValue}
 * for a delete.
 */
@Getter
@Setter
@Entity
@Table(name = "data_changes")
public class DataChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Shared by every row one transaction wrote. An import of eighty contacts is one of
     * these, and so is a single Save that touched a person and two of their addresses.
     */
    @Column(name = "change_set", nullable = false)
    private UUID changeSet;

    /** As the API names it — {@code company}, {@code person}, {@code contact}. */
    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /**
     * What the record was called at the moment it changed. Denormalised deliberately: a
     * deleted record has nothing left to join to, and a log that cannot say which address
     * row 4127 was is a log nobody reads twice.
     */
    @Column(name = "entity_label")
    private String entityLabel;

    /** {@code create}, {@code update} or {@code delete}. */
    @Column(nullable = false)
    private String operation;

    /** The Java property name, or null when the row is a whole-record create or delete. */
    @Column(name = "field_name")
    private String fieldName;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    /** The logged-in username, or null for a change with nobody behind it. */
    @Column(name = "changed_by")
    private String changedBy;

    /** Why, when something bothered to say — "Import of contacts-2026-08-23.csv". */
    @Column(name = "context")
    private String context;
}
