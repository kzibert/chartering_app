package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One test inside a rule: a field, an operator, and the text to look for.
 *
 * <p>Conditions are rows rather than columns on the rule so that a rule with one test and a
 * rule with five are the same kind of object — adding another test is an insert, not a
 * migration.
 */
@Getter
@Setter
@Entity
@Table(name = "mail_rule_conditions")
public class MailRuleCondition {

    /** What part of the message the condition reads. */
    public enum Field {
        /** The sender's address, and their display name with it. */
        FROM,
        /**
         * Only the part after the @. Kept apart from {@link #FROM} because "everything from
         * this company" is the commonest rule there is, and writing it as FROM contains
         * "@example.com" would also match a display name that merely quotes the domain.
         */
        FROM_DOMAIN,
        TO,
        SUBJECT,
        /** The message text. The one field whose test is expensive — see MailRuleService. */
        BODY,
        /** Sender, recipients, subject and body together. */
        ANY
    }

    public enum Operator {
        CONTAINS, NOT_CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private MailRule rule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Field field = Field.FROM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Operator operator = Operator.CONTAINS;

    @Column(nullable = false, columnDefinition = "text")
    private String value;
}
