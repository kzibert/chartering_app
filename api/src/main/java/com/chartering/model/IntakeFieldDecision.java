package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A figure this desk has already looked at and declined, from the firm that reported it.
 *
 * <p><b>Why the queue needed one.</b> A broker's position list arrives every morning and is
 * mostly yesterday's list again. Where his reading of a hull disagrees with the record, the
 * reviewer answers it once — and the next morning the identical email raises the identical
 * question, because the answer was written on the item and the item was closed. ANGORA asked
 * about JELENA's bale capacity four days running; nothing anywhere recorded that this value,
 * from this firm, about this field, had been settled.
 *
 * <p><b>Only a decision that leaves a disagreement standing is stored.</b> Accepting the
 * email's figure writes it to the record, so tomorrow's list agrees and there is nothing to
 * raise — that case needs no row and gets none. Keeping what was on file, or correcting to a
 * third value, leaves the email exactly as wrong tomorrow as it was today, and those are the
 * two this table remembers.
 *
 * <p><b>Scoped to the correspondent, which is the whole of its judgement.</b> That one broker
 * is wrong about her bale says nothing about the next one: a second firm reporting the same
 * figure is a second opinion nobody here has weighed yet, and suppressing it would hide the
 * corroboration that would have settled the question the other way. The same reasoning
 * everywhere else in this feature scopes superseding a position to its own reporter.
 *
 * <p><b>It is not a promise that the answer stays right.</b> A decision suppresses the
 * question, not the fact: if somebody later edits the field by hand to the value this row
 * declined, the email agrees with the record and nothing is raised anyway. What it cannot do
 * is notice that the record moved <em>away</em> from the correction and re-ask — which is the
 * honest cost of storing an answer rather than re-deriving one, and is why the drawer prints
 * what was decided rather than hiding it.
 *
 * <p>Not audited, for the reason nothing else in {@code intake_*} is: it is the record of one
 * event already, and the write it describes — to {@code Vessel} — is audited where it lands.
 */
@Getter
@Setter
@Entity
@Table(name = "intake_field_decisions")
public class IntakeFieldDecision {

    /** The record was right and the email was not. */
    public static final String KEPT = "KEPT";

    /** Neither side was: the reviewer typed a third value. */
    public static final String CORRECTED = "CORRECTED";

    /**
     * The email was right and the record now says so.
     *
     * <p>Written since V28, and only for what it says about the value it <em>replaced</em>:
     * tomorrow's copy of the same list agrees with the record and raises nothing, but another
     * broker still reporting the old figure is now repeating a value the desk has already
     * weighed and moved away from.
     */
    public static final String ACCEPTED = "ACCEPTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    /** The entity property name — what {@code VesselFieldDiff} calls it. */
    @Column(nullable = false, length = 60)
    private String field;

    /**
     * Who reported the declined value.
     *
     * <p>Nullable because the mail sync cannot always put an address to a firm, and a decision
     * about an unidentified sender is still worth keeping — it is the same address writing
     * again tomorrow. The unique index over it is partial for that reason.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_company_id")
    private Company reportedByCompany;

    /**
     * The email's figure as it was compared: no unit, no separators, a capacity already in
     * cubic metres. Text because one table serves a deadweight, an ice class and a boolean;
     * it is parsed back to the field's own type before being compared, so the comparison keeps
     * the tolerance the diff uses rather than becoming a string match.
     */
    @Column(name = "value_text", nullable = false, columnDefinition = "text")
    private String valueText;

    @Column(nullable = false, length = 20)
    private String decision;

    /** Only on {@link #CORRECTED}: what went into the column instead. */
    @Column(name = "corrected_to", columnDefinition = "text")
    private String correctedTo;

    /**
     * What the record held before an {@link #ACCEPTED} or {@link #CORRECTED} answer moved it,
     * canonically. Null on {@link #KEPT}, where the record did not move.
     */
    @Column(name = "replaced_value", columnDefinition = "text")
    private String replacedValue;

    @Column(name = "intake_item_id")
    private Long intakeItemId;

    @Column(name = "decided_at", nullable = false)
    private OffsetDateTime decidedAt = OffsetDateTime.now();

    @Column(name = "decided_by", length = 255)
    private String decidedBy;
}
