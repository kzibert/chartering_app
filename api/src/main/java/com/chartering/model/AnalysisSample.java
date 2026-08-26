package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One email kept as training data, with what a model should make of it.
 *
 * <p><b>Deliberately not a {@link MailMessage}, and not a view over one.</b> That table is a
 * mirror of the IMAP server, written only by the sync, and its rows come and go with the
 * mailbox — a folder cleaned out at the provider, a re-sync after a UIDVALIDITY reset, a
 * retention rule nobody connected to a dataset. A corpus built on top of it would quietly
 * lose examples, and the annotation would go with them: the expensive half of a sample is
 * not the email, it is the human judgement typed underneath it.
 *
 * <p>So the sample carries its own copy of the text. {@link #mailMessage} beside it is
 * provenance — "this came from that message, if you still want to look at it" — and it is
 * {@code ON DELETE SET NULL} for exactly that reason.
 *
 * <p>Not audited, for the same reason {@code CirculationListEntry} is not: this is a working
 * document, and one capture run would write eighty change rows to record that a machine
 * copied eighty emails the database already held.
 */
@Getter
@Setter
@Entity
@Table(name = "analysis_samples")
public class AnalysisSample {

    /** Captured from synced mail. */
    public static final String SOURCE_MAILBOX = "MAILBOX";

    /**
     * Typed or pasted in by hand. How a corpus gets started on a machine whose IMAP is not
     * configured, and the only way an example from somebody else's screen gets in at all.
     */
    public static final String SOURCE_PASTED = "PASTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Where it came from, if that message is still on file. Provenance, not storage. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mail_message_id")
    private MailMessage mailMessage;

    @Column(nullable = false, length = 20)
    private String source = SOURCE_MAILBOX;

    /**
     * The original's Message-ID, and the dedupe key — the same one the mail sync uses, for
     * the same reason: it survives a re-fetch, so capturing a folder twice adds nothing the
     * second time.
     */
    @Column(name = "message_id", length = 998)
    private String messageId;

    @Column(name = "from_address", length = 320)
    private String fromAddress;

    @Column(name = "from_name", length = 255)
    private String fromName;

    @Column(columnDefinition = "text")
    private String subject;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /**
     * The snapshot that is actually trained on. Plain text, never the HTML part: the two
     * carry the same words, and the markup is noise the model would have to learn to ignore
     * before it could learn anything worth knowing.
     */
    @Column(name = "body_text", nullable = false, columnDefinition = "text")
    private String bodyText;

    /**
     * Names only, as in {@link MailMessage}. Worth keeping: a position list that arrived as
     * a PDF is why the body is three lines long, and a sample with an empty body and an
     * attachment is a fact about the dataset rather than a bug in the capture.
     */
    @Column(name = "attachment_names", columnDefinition = "text")
    private String attachmentNames;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AnalysisLabel label = AnalysisLabel.UNLABELLED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status = AnalysisStatus.NEW;

    /**
     * The target output: the JSON a model should produce for this email.
     *
     * <p>Text holding JSON rather than a {@code jsonb} column. Nothing queries into it, the
     * shape of an extraction is still being worked out, and a shape still moving must not
     * need a migration each time it moves. The service checks that it parses before storing
     * it, so an export can never emit a line a trainer chokes on.
     */
    @Column(columnDefinition = "text")
    private String annotation;

    /** Why this one is odd, in words. For the person labelling; never exported. */
    @Column(columnDefinition = "text")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;
}
