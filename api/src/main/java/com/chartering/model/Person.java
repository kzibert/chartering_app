package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "people")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** Honorific (Mr./Mrs./Capt./Sir...) extracted from the source name. */
    @Column(name = "title")
    private String title;

    /**
     * The position this person holds at {@link #company} — "Chartering Manager",
     * "Operations", "Managing Director".
     *
     * <p>Nothing to do with {@link #title}, which is the honorific printed before the
     * greeting name. Neither can be derived from the other, and a screen showing one where
     * the other belongs is wrong in a way that reads as correct.
     *
     * <p>Kept here rather than on each contact because a position is a fact about the
     * person: their mobile and their two mailboxes all carry the same one, and holding
     * three copies of it is three chances to disagree. Contacts read it through the person,
     * as they already do for {@link #hasLeft} and the greeting fallback. A company-wide
     * address — a chartering@ desk, filed under no person — therefore has none, which is
     * right: a desk is not a job somebody holds.
     */
    @Column(name = "job_title")
    private String jobTitle;

    /** English first name used for greetings in circulation emails. */
    @Column(name = "greeting_name")
    private String greetingName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    private String notes;

    @Column(name = "legacy_id")
    private Long legacyId;

    /** true = imported legacy row; false = created in-app (new data). Defaults to new. */
    @Column(name = "is_legacy", nullable = false)
    private boolean legacy = false;

    /**
     * This person no longer works at {@link #company}, so none of their addresses may be
     * circulated to.
     *
     * <p>A flag rather than a delete or a company change, because the record still has work
     * to do: circulation history references the person by id, the addresses stay the right
     * ones to search a mailbox for, and "who did we deal with there before?" has a real
     * answer. What has to stop is the mail — a cargo offer landing in the inbox of somebody
     * who left, forwarded around a company we are trying to trade with, is worse than not
     * sending it.
     *
     * <p>Reaches every contact of theirs at once, which is the point: departure is a fact
     * about the person, and flagging their addresses one by one would be the same statement
     * made five times and forgotten on the sixth. It is deliberately not copied down onto
     * the contact rows — see db/person_left_company.sql.
     *
     * <p>Honoured in the same two places as the contact-level exclusions: left out of bulk
     * collection, and dropped again when a campaign starts or resumes, so an address already
     * sitting on a saved list or the current draft still cannot be mailed.
     */
    @Column(name = "has_left", nullable = false)
    private boolean hasLeft = false;
}
