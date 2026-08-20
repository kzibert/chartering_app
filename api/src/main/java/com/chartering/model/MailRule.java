package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * "If a message matches, file it into this folder."
 *
 * <p>Rules run in {@link #sortOrder}, and the <em>first</em> match wins — no later rule is
 * consulted. A message lives in exactly one folder, so letting every matching rule apply
 * would really mean "the last one applies" while reading as though it meant something more.
 *
 * <p>They are evaluated against the stored row, not against a live IMAP message, which is
 * what allows a rule written today to be re-run over mail that arrived last week.
 */
@Getter
@Setter
@Entity
@Table(name = "mail_rules")
public class MailRule {

    /** Whether every condition has to match, or just one of them. */
    public enum MatchType {ALL, ANY}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    /** Where matching mail goes. A rule that files nowhere is not a rule. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private MailFolder folder;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Lowest first. Also the tie-break for which rule claims a message. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 10)
    private MatchType matchType = MatchType.ALL;

    /** File it and count it as already read — for the mail you keep but never open. */
    @Column(name = "mark_read", nullable = false)
    private boolean markRead = false;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    private List<MailRuleCondition> conditions = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public void addCondition(MailRuleCondition c) {
        c.setRule(this);
        conditions.add(c);
    }

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
