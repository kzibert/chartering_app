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

    /**
     * "Use this address when circulating." Unlike {@link #main} there may be any number per
     * company or person — a desk with three chartering addresses wants all three mailed,
     * which is a different question from "one address to reach them on".
     *
     * <p>Flagging one narrows collection for its group only; see
     * {@code RecipientSelectionService} for the circ &gt; main &gt; all precedence.
     */
    @Column(name = "is_circ", nullable = false)
    private boolean circ = false;

    /**
     * "Never circulate to this address." A working address that must stay out of bulk mail —
     * an accounts@ or ops@ inbox, or somebody who asked to be taken off the circular.
     *
     * <p>Not the same as {@link #working} = false, and the difference is worth keeping: a
     * dead address cannot receive anything, while this one is still the right address to
     * write to by hand. Marking a live mailbox dead to keep it off a circular would lose
     * that, and would leave nobody able to tell a bounce from a deliberate exclusion.
     *
     * <p>The exact opposite of {@link #circ}, so setting either clears the other. Honoured
     * twice: in bulk collection, and again at send time, so an address already sitting in a
     * saved list still cannot be mailed.
     */
    @Column(name = "is_no_circ", nullable = false)
    private boolean noCirc = false;

    /**
     * This number has a WhatsApp account behind it. Phone contacts only.
     *
     * <p>Recorded by hand rather than detected: there is no open way to ask WhatsApp whether
     * a number is registered, so the app opens a wa.me link with a greeting prefilled and
     * the user says whether a chat came up. It is somebody's observation, not a fact the
     * system can verify or refresh — which is why nothing ever clears it automatically.
     */
    @Column(name = "has_whatsapp", nullable = false)
    private boolean hasWhatsapp = false;
}
