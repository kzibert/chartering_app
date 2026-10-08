package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

/**
 * A broker relationship between a company and a vessel.
 *
 * Ownership is not stored here — it lives in {@link Vessel#getOwner()} — so that each
 * fact has exactly one home. See db/vessel_company_links.sql.
 */
@Getter
@Setter
@Entity
@Table(name = "vessel_company_links")
public class VesselCompanyLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The desk this row belongs to. Written and filtered by Hibernate - see TenantIdentifierResolver. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** 'exclusive_broker' or 'broker'. */
    @Column(nullable = false)
    private String role;

    private String notes;
}
