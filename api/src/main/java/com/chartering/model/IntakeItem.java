package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One thing the parser would not decide on its own.
 *
 * <p><b>{@link #payload} is JSON held as text, and nothing queries inside it.</b> That is
 * deliberate, and it is the difference between this table and the ones it feeds: an item is
 * a <em>proposal</em>, with a lifetime measured in days, whose shape follows the extraction
 * template — and that template is still moving. A column per field would mean a migration
 * every time the model learns to read one more thing about a ship, to store rows that will
 * be answered and closed before the migration is a month old. The same argument
 * {@code analysis_samples.annotation} makes, for the same reason.
 *
 * <p>{@link #vesselId} and {@link #cargoId} are plain ids rather than associations, and both
 * are nullable: which of them is set is what the {@link IntakeItemKind} means. A
 * {@code NEW_VESSEL} has no vessel yet — that is the question — and a rejected one never
 * gets one, which is why {@link #subjectLabel} carries the name as the email spelled it. The
 * columns are {@code ON DELETE SET NULL} for the matching reason: deleting a ship must not
 * silently delete the record of a decision made about her.
 */
@Getter
@Setter
@Entity
@Table(name = "intake_items")
public class IntakeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parsed_email_id", nullable = false)
    private ParsedEmail parsedEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IntakeItemKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IntakeItemStatus status = IntakeItemStatus.PENDING;

    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "cargo_id")
    private Long cargoId;

    /** How the queue reads as a list of ships and cargoes rather than a list of ids. */
    @Column(name = "subject_label", length = 255)
    private String subjectLabel;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
