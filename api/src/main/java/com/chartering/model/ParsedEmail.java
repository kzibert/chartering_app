package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One message the parser has read — and the record that it has been read.
 *
 * <p><b>This row is the dedupe.</b> The sweep asks for synced mail with no row here, which
 * is what makes running it on a timer, running it from the button, and running it twice all
 * safe. There is no queue table and no "pending" state: a message is either read or it is
 * not, and the absence of a row is what "not" looks like.
 *
 * <p><b>{@link #rawJson} is kept even though the useful half has already been applied.</b>
 * The positions and cargoes a parse produced are in their own tables and are what the desk
 * works with; this is what they were derived from. It is the only thing that can settle the
 * question that actually comes up — did the model read the email wrong, or did we file its
 * answer wrong — and without it that question is unanswerable a week later, because the
 * email says one thing and the row says another and nothing shows the step in between.
 *
 * <p>The three counters are denormalised on purpose. Two of the three cannot be recovered by
 * counting afterwards: a cargo merged into an existing one leaves no row pointing back here,
 * and a position skipped as an exact re-report leaves nothing at all.
 */
@Getter
@Setter
@Entity
@Table(name = "parsed_emails")
public class ParsedEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_message_id", nullable = false)
    private MailMessage mailMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParseStatus status = ParseStatus.PARSED;

    /**
     * The model's own classification — cargo_offer, vessel_opening, mixed, other — as it
     * said it, not mapped onto an enum here. It is evidence about the parse rather than a
     * fact about the email, and "other" is what explains a message that produced nothing.
     */
    @Column(name = "email_type", length = 30)
    private String emailType;

    @Column(name = "raw_json", columnDefinition = "text")
    private String rawJson;

    @Column(name = "model_name", length = 160)
    private String modelName;

    @Column(name = "prompt_chars")
    private Integer promptChars;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "positions_applied", nullable = false)
    private int positionsApplied = 0;

    @Column(name = "cargoes_applied", nullable = false)
    private int cargoesApplied = 0;

    @Column(name = "items_raised", nullable = false)
    private int itemsRaised = 0;

    /** In the words the user has to act on: "Connect timed out" means the box is asleep. */
    @Column(columnDefinition = "text")
    private String error;

    @Column(nullable = false)
    private int attempts = 1;

    @Column(name = "parsed_at", nullable = false)
    private OffsetDateTime parsedAt = OffsetDateTime.now();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
