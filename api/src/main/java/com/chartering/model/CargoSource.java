package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One arrival of one cargo: who told us, out of which email, and when.
 *
 * <p><b>Why a cargo needs this and a position does not.</b> A {@link VesselPosition} is
 * already one row per report carrying its own reporter, and Open Fleet collapses them to the
 * newest per hull — the combined view and every source behind it both exist there already. A
 * cargo is the opposite shape: it is one record that several brokers describe, and it has to
 * stay one record, because the same cargo listed three times on the Cargoes tab is three
 * chances to offer the same ship against it. So the merge happens on the cargo and the
 * provenance moves here.
 *
 * <p>Not a replacement for {@link Cargo#getBrokerCompany()}: that is who this cargo is
 * worked through, an editable fact about the cargo that a person may correct. This is a log
 * of what arrived, and nothing edits it.
 */
@Getter
@Setter
@Entity
@Table(name = "cargo_sources")
public class CargoSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cargo_id", nullable = false)
    private Cargo cargo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mail_message_id")
    private MailMessage mailMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_company_id")
    private Company reportedByCompany;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_person_id")
    private Person reportedByPerson;

    /**
     * Kept as text as well as through the link. A sender with no contact row still has to be
     * attributable, and when there is no company this is the whole of what we know.
     */
    @Column(name = "from_address", length = 320)
    private String fromAddress;

    @Column(name = "reported_at", nullable = false)
    private OffsetDateTime reportedAt = OffsetDateTime.now();

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
