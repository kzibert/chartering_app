package com.chartering.model;

import com.chartering.tenancy.Owned;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * Where the IMAP reader got to in one mailbox folder, and how the last attempt went.
 *
 * <p>The outcome is recorded, not just the cursor, because a mailbox tab that has silently
 * stopped syncing looks exactly like a quiet morning. The status is what lets the screen say
 * "last sync failed, and here is the error" instead of presenting stale mail as current.
 */
@Getter
@Setter
@Entity
@Table(name = "mail_sync_state")
@EntityListeners(Owned.Stamp.class)
@Filter(name = Owned.FILTER)
public class MailSyncState implements Owned {

    /** How the last attempt ended. */
    public static final String OK = "OK";
    public static final String FAILED = "FAILED";

    /** The IMAP folder read, e.g. {@code INBOX}. The folder is the identity. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "imap_folder", nullable = false, length = 255)
    private String imapFolder;

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
     * The server's UIDVALIDITY at the last read. When it changes, every UID stored against
     * this folder means something else, so the reader must fall back to the date window
     * instead of resuming from {@link #lastUid} — recording it is what makes that
     * detectable rather than a silent gap in the inbox.
     */
    @Column(name = "imap_validity")
    private Long imapValidity;

    @Column(name = "last_uid")
    private Long lastUid;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "last_status", length = 20)
    private String lastStatus;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** How many messages the last attempt read, and how many of those were new. */
    @Column(name = "last_fetched", nullable = false)
    private int lastFetched = 0;

    @Column(name = "last_stored", nullable = false)
    private int lastStored = 0;
}
