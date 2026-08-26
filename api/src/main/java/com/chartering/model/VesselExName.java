package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A name a vessel used to carry.
 *
 * <p>Ships are renamed by their owners on every sale and every change of manager, and a
 * position list may use a name this database has never seen for a hull it has held for
 * years. The IMO number is the only identifier that never moves, and it is exactly what a
 * broker's circular leaves out — so the former names are what makes the ship findable.
 *
 * <p>299 of these were extracted by V11 out of vessel names people had typed the history
 * into ("LOIRE RIVER/ EX AMIKO"). Those carry {@code source = "backfill"}: a machine's
 * reading of a free-text field, and the first thing to suspect if a vessel ever looks
 * wrong. Anything added since is {@code "manual"}.
 */
@Getter
@Setter
@Entity
@Table(name = "vessel_ex_names")
public class VesselExName {

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_BACKFILL = "backfill";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 20)
    private String source = SOURCE_MANUAL;

    /**
     * When she was renamed, if anybody knows. Almost never filled from a backfill — the old
     * name was recorded, the date it stopped being current was not.
     */
    @Column(name = "renamed_at")
    private LocalDate renamedAt;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
