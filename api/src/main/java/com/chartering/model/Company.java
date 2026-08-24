package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_shipowner", nullable = false)
    private boolean shipowner = false;

    @Column(name = "is_charterer", nullable = false)
    private boolean charterer = false;

    @Column(name = "is_broker", nullable = false)
    private boolean broker = false;

    @Column(name = "is_agent", nullable = false)
    private boolean agent = false;

    /** One person is the whole business. Set by hand only — never inferred from the data. */
    @Column(name = "is_solo", nullable = false)
    private boolean solo = false;

    @Column(name = "city_name")
    private String cityName;

    /**
     * Where the company is, at country granularity. A plain string like {@link #cityName}
     * and for the same reason: it arrives spelled however the source spelled it, and a
     * lookup table would mean inventing a canonical row for every variant before an import
     * could store anything.
     *
     * <p>There is deliberately no street or postcode to go with it. Nothing here posts
     * anything; city and country are what a chartering desk uses, and a half-filled address
     * block only invites the reader to trust it as a whole one.
     */
    @Column(name = "country")
    private String country;

    /**
     * The company's own site, stored bare — {@code fednav.com}, not {@code https://…}. That
     * is how it arrives, and the scheme is the one part of it that never tells you anything;
     * the UI puts one back when it makes a link.
     */
    @Column(name = "website")
    private String website;

    private String notes;

    @Column(name = "legacy_id")
    private Long legacyId;

    // ---- confirm block ----
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

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "company_ports",
            joinColumns = @JoinColumn(name = "company_id"),
            inverseJoinColumns = @JoinColumn(name = "port_id"))
    private Set<Port> ports = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "company_tonnage_categories",
            joinColumns = @JoinColumn(name = "company_id"),
            inverseJoinColumns = @JoinColumn(name = "tonnage_category_id"))
    private Set<TonnageCategory> tonnageCategories = new HashSet<>();
}
