package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "vessels")
public class Vessel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "imo_number")
    private String imoNumber;

    @Column(name = "deadweight_tonnage")
    private BigDecimal deadweightTonnage;

    @Column(name = "deadweight_cargo_capacity")
    private BigDecimal deadweightCargoCapacity;

    @Column(name = "grain_capacity_m3")
    private BigDecimal grainCapacityM3;

    @Column(name = "bale_capacity_m3")
    private BigDecimal baleCapacityM3;

    @Column(name = "maximum_draft")
    private BigDecimal maximumDraft;

    @Column(name = "year_built")
    private Integer yearBuilt;

    @Column(name = "vessel_type")
    private String vesselType;

    private String flag;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Company owner;

    private String notes;

    @Column(name = "legacy_id")
    private Long legacyId;

    // ---- "reached again & confirmed up to date" block ----
    @Column(name = "is_confirmed", nullable = false)
    private boolean confirmed = false;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "confirmed_by")
    private String confirmedBy;

    @Column(name = "confirm_notes")
    private String confirmNotes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
