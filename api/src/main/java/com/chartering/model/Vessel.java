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

    // ---- what a charterer asks about before anything else ----
    //
    // Every one of these is nullable, and null means "not on file" rather than "no". That
    // distinction is the same one the existing figures make by storing 0 for an unknown
    // capacity, and it matters more here because these are booleans: false would be a claim
    // about four thousand rows nobody has checked. Match reads a null as a question to
    // raise, never as a knockout.

    /** True/false where a list has actually said so; null where nothing has. */
    private Boolean geared;

    /**
     * What the list said, verbatim: "2x30T CRANES", "3 x 12,5 t derricks", "GEARLESS",
     * "cranes fitted grabs 2x6cbm". A column of enumerated crane types would discard most
     * of that, and the discarded half is the part a charterer reads.
     */
    @Column(name = "gear_description", length = 160)
    private String gearDescription;

    private Short holds;

    private Short hatches;

    // Three separate facts rather than one list, because circulars negate them one at a
    // time: "imo-timber-not grain ftd" is a real line, and it is the "not grain" that
    // decides whether a wheat cargo can be offered.

    @Column(name = "grain_fitted")
    private Boolean grainFitted;

    @Column(name = "timber_fitted")
    private Boolean timberFitted;

    @Column(name = "imo_fitted")
    private Boolean imoFitted;

    /**
     * Free text: the class societies do not agree on one scale, and 1A, 1A Super, E3 and
     * "ice class - no" all appear in this trade's mail.
     */
    @Column(name = "ice_class", length = 20)
    private String iceClass;

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

    /** Russian-rooted entity excluded from filters by default (see db/banned_flags.sql). */
    @Column(nullable = false)
    private boolean banned = false;

    /** true = imported legacy row; false = created in-app (new data). Defaults to new. */
    @Column(name = "is_legacy", nullable = false)
    private boolean legacy = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
