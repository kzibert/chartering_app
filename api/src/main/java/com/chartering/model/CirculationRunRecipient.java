package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One address a circulation touched, together with the merge fields it was rendered with.
 *
 * <p>Addresses the run deliberately did <em>not</em> mail are recorded too: "who was skipped
 * and why" is as much part of the audit trail as "who received it", and it is the question
 * you actually ask when a circular has to be re-sent.
 */
@Getter
@Setter
@Entity
@Table(name = "circulation_run_recipients")
public class CirculationRunRecipient {

    /** Queued when the run started, never reached — the run ended before getting here. */
    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED_DUPLICATE = "SKIPPED_DUPLICATE";
    public static final String SKIPPED_NOT_WORKING = "SKIPPED_NOT_WORKING";
    /** Address flagged "not for circ" — it works, it is simply never bulk-mailed. */
    public static final String SKIPPED_NOT_FOR_CIRC = "SKIPPED_NOT_FOR_CIRC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private CirculationRun run;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private String email;

    @Column(name = "contact_id")
    private Long contactId;

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

    @Column(nullable = false)
    private String status = PENDING;

    /** How many deliveries were attempted, so a retried address is visible as such. */
    @Column(nullable = false)
    private int attempts = 0;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /**
     * Which flow this message left by - see {@code CircularProvider}. Written as the message
     * goes out, so it records the route actually taken rather than whatever Settings said
     * when the run opened: a circulation paused under the mailbox flow and resumed after the
     * switch genuinely left by two routes, and only a per-recipient column can say so.
     */
    @Column(nullable = false, length = 10)
    private String provider = "SMTP";
}
