package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A desk: the unit data is separated by. Everybody on one desk sees the same companies,
 * vessels and cargoes; nobody sees another desk's.
 *
 * <p>Deliberately not tenant-scoped itself - the login has to find a user and their desk
 * before any tenant is known, and the platform administrator lists them all.
 */
@Getter
@Setter
@Entity
@Table(name = "tenants")
public class Tenant {

    /** The desk an existing single-desk database becomes on upgrade (V30). */
    public static final long DEFAULT_ID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantStatus status = TenantStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
