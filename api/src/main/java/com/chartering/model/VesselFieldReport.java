package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One firm's figure for one particular of one hull, and how often we have heard it.
 *
 * <p><b>What the market says, beside what the desk decided.</b> {@link IntakeFieldDecision}
 * records answers; this records statements — every value a correspondent has reported for a
 * field, agreeing with the record or not. It is what lets a disagreement be <em>weighed</em>
 * rather than merely detected: three brokers reporting what is on file against one reporting
 * something else is a different question from one broker correcting a figure nobody else has
 * ever confirmed, and {@code VesselReviewPolicy} tells them apart from these rows.
 *
 * <p>One row per hull, field, firm and value rather than per email. A position list arrives
 * every morning, and a row per arrival would be the same statement written thirty times a
 * month; {@link #timesSeen} and {@link #lastSeenAt} keep what the repeats are worth.
 *
 * <p>Not audited, for the reason nothing in {@code intake_*} is: a machine's record of what
 * it read.
 */
@Getter
@Setter
@Entity
@Table(name = "vessel_field_reports")
public class VesselFieldReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    /** The entity property name — what {@code VesselFieldDiff} calls it. */
    @Column(nullable = false, length = 60)
    private String field;

    /** Null where the sync could not place the sender; still one voice rather than none. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_company_id")
    private Company reportedByCompany;

    /** Canonical: the figure as compared, no unit, a capacity already in cubic metres. */
    @Column(name = "value_text", nullable = false, columnDefinition = "text")
    private String valueText;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "times_seen", nullable = false)
    private int timesSeen = 1;

    @Column(name = "last_parsed_email_id")
    private Long lastParsedEmailId;

    public Long companyId() {
        return reportedByCompany == null ? null : reportedByCompany.getId();
    }
}
