package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One synced incoming message.
 *
 * <p>Headers and both body parts are stored; attachments are not (their names are, so it is
 * at least visible that the message had them). Everything below the header block is the
 * app's own state — read/unread, which folder it sits in, which company it belongs to — and
 * none of it is ever written back to the mail server, which is opened read-only.
 *
 * <p><b>Identity.</b> {@link #messageId} is the dedupe key: it is the only identifier that
 * survives a re-fetch, a re-index, or the provider resetting UIDVALIDITY, and deduping on it
 * is what makes re-syncing idempotent rather than doubling the inbox. The IMAP UID is kept
 * as the incremental cursor, and as the fallback identity for the rare message that arrives
 * with no Message-ID at all.
 */
@Getter
@Setter
@Entity
@Table(name = "mail_messages")
public class MailMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ---- identity on the server ----

    @Column(name = "message_id", length = 998)
    private String messageId;

    @Column(name = "imap_uid")
    private Long imapUid;

    @Column(name = "imap_validity")
    private Long imapValidity;

    @Column(name = "imap_folder", nullable = false, length = 255)
    private String imapFolder = "INBOX";

    // ---- headers ----

    @Column(name = "from_address", nullable = false, length = 320)
    private String fromAddress;

    @Column(name = "from_name", length = 255)
    private String fromName;

    /** Comma-joined. Displayed and substring-searched, never joined on. */
    @Column(name = "to_addresses", columnDefinition = "text")
    private String toAddresses;

    @Column(name = "cc_addresses", columnDefinition = "text")
    private String ccAddresses;

    @Column(columnDefinition = "text")
    private String subject;

    /**
     * The Date header — when the sender says it was sent. Kept alongside {@link #receivedAt}
     * rather than instead of it: a wrong clock on the sending side is common enough that
     * ordering by this alone pins the odd message to the top of the inbox forever.
     */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** When the server received it. This is what the inbox is ordered by. */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    // ---- body ----

    @Column(name = "body_text", columnDefinition = "text")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    /** First line or so, so listing the inbox never reads a megabyte per row for a preview. */
    @Column(length = 300)
    private String snippet;

    @Column(name = "has_attachments", nullable = false)
    private boolean hasAttachments = false;

    @Column(name = "attachment_names", columnDefinition = "text")
    private String attachmentNames;

    @Column(name = "size_bytes")
    private Integer sizeBytes;

    // ---- app-owned state ----

    /**
     * Seeded once from the server's \Seen flag when the row is first written, and the app's
     * own from then on. It cannot be anything else: the app never writes to the mailbox, so
     * following the server afterwards would keep resetting what was read here.
     */
    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    /** NULL = the Inbox, which is to say nothing has filed it yet. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private MailFolder folder;

    /** Which rule filed it, for "why is this here?". Not a foreign key — see db/mailbox.sql. */
    @Column(name = "filed_by_rule_id")
    private Long filedByRuleId;

    @Column(name = "filed_at")
    private LocalDateTime filedAt;

    // ---- the link to the rest of the app ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id")
    private Person person;

    /**
     * true = a human set this link. The automatic re-link pass leaves those alone, so
     * correcting a wrong guess by hand is permanent rather than undone by the next sync.
     */
    @Column(name = "link_manual", nullable = false)
    private boolean linkManual = false;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt = LocalDateTime.now();
}
