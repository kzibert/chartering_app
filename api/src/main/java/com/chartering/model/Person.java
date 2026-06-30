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

    /** English first name used for greetings in circulation emails. */
    @Column(name = "greeting_name")
    private String greetingName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    private String notes;

    @Column(name = "legacy_id")
    private Long legacyId;
}
