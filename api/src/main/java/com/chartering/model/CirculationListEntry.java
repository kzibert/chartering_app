package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One address on a circulation list, with the mail-merge fields snapshotted beside it.
 *
 * <p>The snapshot is deliberate: a list is a prepared document, so editing a greeting for
 * one circular must not write back to the person's record, and a later rename of the
 * company must not silently change a list you already reviewed. {@code contactId} keeps
 * the trail back to the source contact when there is one — hand-typed rows leave it null.
 */
@Getter
@Setter
@Entity
@Table(name = "circulation_list_entries")
public class CirculationListEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "list_id", nullable = false)
    private CirculationList list;

    /** Source contact, or null once that contact is deleted (FK is ON DELETE SET NULL). */
    @Column(name = "contact_id")
    private Long contactId;

    @Column(nullable = false)
    private String email;

    @Column(name = "person_id")
    private Long personId;

    @Column(name = "person_name")
    private String personName;

    @Column(name = "greeting_name")
    private String greetingName;

    private String title;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
