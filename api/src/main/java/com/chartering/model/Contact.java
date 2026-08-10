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

    /** true = imported legacy row; false = created in-app (new data). Defaults to new. */
    @Column(name = "is_legacy", nullable = false)
    private boolean legacy = false;

    /**
     * The company's default contact of this kind — at most one main email and one main
     * phone per company (enforced by ux_contacts_main_per_company_kind). Bulk email-list
     * actions prefer it and fall back to the company's first email when nothing is flagged.
     */
    @Column(name = "is_main", nullable = false)
    private boolean main = false;

    /**
     * false = the address/number is dead (bounced, disconnected). Stored positively and
     * defaulting to true so untouched rows stay reachable. Non-working emails are left out
     * of bulk email-list collection and dropped again at campaign send time.
     */
    @Column(name = "is_working", nullable = false)
    private boolean working = true;
}
