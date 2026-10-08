package com.chartering.model;

import com.chartering.tenancy.Owned;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * One reply this app sent to a message in the mailbox.
 *
 * <p><b>Deliberately not a row in {@link MailMessage}.</b> That table is a mirror of what
 * the mail server holds, written only by the sync; a row this application invented would be
 * a message no folder contains. The provider's own copy of this reply arrives in the Sent
 * folder and is synced like anything else, in its own time.
 *
 * <p>Which is what makes this table worth keeping even so. It is written the moment the
 * send returns, so the day's outgoing count is right immediately instead of at the next
 * poll; it is still right on a provider that keeps no Sent copy at all; and it is the only
 * place that records which message a reply answered, since In-Reply-To is not among the
 * headers the sync stores.
 *
 * <p>Not audited. Like the synced mail it hangs off, this is already a record of an event
 * rather than an editable record — nothing here is ever changed after the insert, so a
 * change log of it would hold one create row per reply and nothing else.
 */
@Getter
@Setter
@Entity
@Table(name = "mail_replies")
@EntityListeners(Owned.Stamp.class)
@Filter(name = Owned.FILTER)
public class MailReply implements Owned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The desk this row belongs to. Written and filtered by Hibernate - see TenantIdentifierResolver. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /**
     * Whose mailbox this belongs to. Stamped on insert (Owned.Stamp); null only on rows that
     * predate accounts, until MailOwnership assigns them at startup.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /**
     * The message replied to, or null if it has since been deleted from the mailbox
     * (ON DELETE SET NULL). The reply happened either way, and the day's count must not
     * change because somebody cleaned out an old folder.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mail_message_id")
    private MailMessage mailMessage;

    /**
     * The Message-ID this app put on the reply, so the Sent-folder copy can be recognised
     * as the same message rather than counted as a second one.
     */
    @Column(name = "message_id", length = 998)
    private String messageId;

    @Column(name = "to_address", nullable = false, length = 320)
    private String toAddress;

    /**
     * Everyone copied, comma-joined, or null. Only a message written from a company's record
     * has any: a reply goes to the one address that wrote.
     */
    @Column(name = "cc_addresses", columnDefinition = "text")
    private String ccAddresses;

    @Column(columnDefinition = "text")
    private String subject;

    /** What was actually sent — footer, quoted original and merge included. */
    @Column(name = "body_html", nullable = false, columnDefinition = "text")
    private String bodyHtml;

    /** By name as well as by id, so history survives the footer being deleted. */
    @Column(name = "footer_id")
    private Long footerId;

    @Column(name = "footer_name", length = 150)
    private String footerName;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    @Column(name = "sent_by", length = 255)
    private String sentBy;
}
