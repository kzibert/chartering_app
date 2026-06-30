package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "contacts")
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id")
    private Person person;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "contact_kind", nullable = false)
    private String contactKind;     // 'email' | 'phone'

    @Column(name = "contact_value", nullable = false)
    private String contactValue;

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
}
