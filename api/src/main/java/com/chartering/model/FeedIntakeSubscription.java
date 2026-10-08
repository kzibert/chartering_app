package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.OffsetDateTime;

/**
 * One desk reading one board into Intake: its posts go through the parser as circulars for that
 * desk. The board is global and fetched once; this row is the desk's decision about it (V34).
 *
 * <p>Not audited: it is a switch on a screen, and the change it causes - posts appearing in the
 * Intake queue - is its own record.
 */
@Getter
@Setter
@Entity
@Table(name = "feed_intake_subscriptions")
public class FeedIntakeSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The desk this row belongs to. Written and filtered by Hibernate - see TenantIdentifierResolver. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "feed_source_id", nullable = false, updatable = false)
    private Long feedSourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
