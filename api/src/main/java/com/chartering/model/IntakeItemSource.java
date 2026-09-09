package com.chartering.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One email that raised a review item, of possibly several.
 *
 * <p><b>Why an item has sources at all.</b> A {@code VESSEL_FIELDS} item asks "the record says
 * one thing and the mail says another — which is right?", and that question is about a hull
 * rather than about an email. Several emails ask it: a broker re-sends his list on Monday and
 * again on Wednesday, and two brokers carry the same ship. Each arrival used to raise its own
 * item, so the queue showed one vessel three times and answering one left the others behind.
 *
 * <p>The shape is {@code CargoSource}'s, and deliberately: a cargo is one record several
 * brokers describe, and a disagreement about a ship is the same thing. What it buys is the two
 * things a reviewer wants when the same hull arrives twice — every original email readable from
 * the one item, and every sending firm available to attach to her.
 *
 * <p>{@code IntakeItem.parsedEmail} still names the arrival that first raised the item, and is
 * left alone: it is what the item was created from and what its payload was built out of. The
 * sources are the whole story, and the first one always repeats that column.
 */
@Getter
@Setter
@Entity
@Table(name = "intake_item_sources")
public class IntakeItemSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intake_item_id", nullable = false)
    private IntakeItem intakeItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parsed_email_id", nullable = false)
    private ParsedEmail parsedEmail;

    /**
     * The message itself, where the mailbox still holds it.
     *
     * <p>Nullable and {@code ON DELETE SET NULL}: {@code mail_messages} mirrors a server whose
     * folders get emptied, and that a broker raised this outlives the copy of their email.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mail_message_id")
    private MailMessage mailMessage;

    /** Who sent it — the reason more than one firm can be offered for attaching to the hull. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_company_id")
    private Company reportedByCompany;

    @Column(name = "reported_at", nullable = false)
    private OffsetDateTime reportedAt = OffsetDateTime.now();

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
