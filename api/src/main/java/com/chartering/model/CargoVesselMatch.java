package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * The human's answer about one cargo and one vessel.
 *
 * <p><b>Scores are not stored here, and nothing else stores them either.</b> Match computes
 * on every request. A stored score goes stale the moment a position or a cargo moves, and a
 * table of them would need invalidating on every write in this feature — for a calculation
 * that runs in milliseconds over a few hundred live positions.
 *
 * <p>What does need storing is what was decided. One row per pairing, holding the last thing
 * that happened between them: offering a ship twice is a correction to the first answer, not
 * a second one.
 */
@Getter
@Setter
@Entity
@Table(name = "cargo_vessel_matches")
public class CargoVesselMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cargo_id", nullable = false)
    private Cargo cargo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchOutcome outcome;

    @Column(columnDefinition = "text")
    private String note;

    /**
     * The position this was decided against, so the decision can be read back against what
     * was known at the time rather than against wherever she is now. Nullable and
     * ON DELETE SET NULL: the decision outlives the position being superseded, which it
     * will be.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vessel_position_id")
    private VesselPosition vesselPosition;

    @Column(name = "decided_by")
    private String decidedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
