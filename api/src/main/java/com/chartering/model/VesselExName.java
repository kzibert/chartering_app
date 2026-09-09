package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A name a vessel used to carry.
 *
 * <p>Ships are renamed by their owners on every sale and every change of manager, and a
 * position list may use a name this database has never seen for a hull it has held for
 * years. The IMO number is the only identifier that never moves, and it is exactly what a
 * broker's circular leaves out — so the former names are what makes the ship findable.
 *
 * <p>299 of these were extracted by V11 out of vessel names people had typed the history
 * into ("LOIRE RIVER/ EX AMIKO"). Those carry {@code source = "backfill"}: a machine's
 * reading of a free-text field, and the first thing to suspect if a vessel ever looks
 * wrong. A name typed by a person is {@code "manual"}; one learned from a circular the email
 * parser read is {@code "mail"}.
 *
 * <p>The three are kept apart for the reason the backfill ones were marked in the first
 * place: they are worth different amounts. A person typed the manual ones knowing the ship.
 * The mail ones are a name an email used for a hull somebody then confirmed was this hull —
 * good evidence, but evidence, and the place to look first when a position turns up on a
 * ship that cannot be where it says she is.
 */
@Getter
@Setter
@Entity
@Table(name = "vessel_ex_names")
public class VesselExName {

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_BACKFILL = "backfill";

    /**
     * Learned from a circular: the name an email used for a hull the reviewer then confirmed
     * was this one, on the Intake tab. Filing it is what stops the next list raising the
     * identical question.
     */
    public static final String SOURCE_MAIL = "mail";

    /**
     * Caught as it happened: her record was saved under a new name, and this is the one it
     * carried until then.
     *
     * <p>Its own value rather than {@link #SOURCE_MANUAL} because the two answer different
     * questions. {@code manual} means somebody sat down and entered a former name, which is a
     * statement about the ship's history; this means nobody entered anything — the name field
     * changed on a save and the old value was kept rather than dropped. The distinction matters
     * when a row looks wrong: a typo corrected twice leaves a former name that was never a
     * name, and this source is where to look for it.
     */
    public static final String SOURCE_RENAME = "rename";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 20)
    private String source = SOURCE_MANUAL;

    /**
     * When she was renamed, if anybody knows. Almost never filled from a backfill — the old
     * name was recorded, the date it stopped being current was not.
     */
    @Column(name = "renamed_at")
    private LocalDate renamedAt;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
