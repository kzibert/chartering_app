package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One folder as the mail server reports it — a mirror of the tree in Zoho, refreshed on
 * every sync.
 *
 * <p>The opposite of {@link MailFolder} in every respect, which is why they are two entities
 * rather than one with a flag. An app folder is created here and means nothing to the
 * server; a server folder is created there and nothing in this application can make, rename
 * or delete one. The two are different axes and both apply at once: a message sits in the
 * server folder Zoho's own filters dropped it into, and may additionally be filed by an app
 * rule into an app folder.
 *
 * <p><b>The name is the identity</b>, as it is in {@link MailSyncState}: it is what the
 * server answers with, what {@link MailMessage#getImapFolder()} carries, and the only handle
 * IMAP offers. Decoded from modified UTF-7 on the way in, so this holds "Корзина" rather
 * than "&amp;BBoEPgRABDcEOAQ9BDA-".
 *
 * <p>A folder that stops being listed is marked {@link #present} false rather than deleted.
 * The mail synced out of it is still in the database, and a rail entry with no name to show
 * is worse than one marked as gone.
 */
@Getter
@Setter
@Entity
@Table(name = "mail_server_folders")
public class MailServerFolder {

    /** IMAP SPECIAL-USE, normalised. Absent for a folder the mailbox's owner made. */
    public static final String INBOX = "INBOX";
    public static final String SENT = "SENT";
    public static final String DRAFTS = "DRAFTS";
    public static final String JUNK = "JUNK";
    public static final String TRASH = "TRASH";
    public static final String ARCHIVE = "ARCHIVE";

    @Id
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "parent_name", length = 255)
    private String parentName;

    /** The server's hierarchy delimiter — "/" on Zoho. Per-server, not universal. */
    @Column(length = 4)
    private String separator;

    @Column(name = "special_use", length = 20)
    private String specialUse;

    /** \Noselect folders hold no mail; they exist only as a branch of the tree. */
    @Column(nullable = false)
    private boolean selectable = true;

    /**
     * What the server said it holds when last listed. Kept beside the app's own counts
     * because the two answer different questions: how much mail is in this folder, and how
     * much of it has been synced. A folder showing 26 on the server and nothing here has not
     * been reached yet, which otherwise looks exactly like an empty folder.
     */
    @Column(name = "server_total")
    private Integer serverTotal;

    @Column(name = "server_unseen")
    private Integer serverUnseen;

    /** Inbox first, then the owner's own folders, then Drafts/Sent, then Junk and Trash. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    @Column(nullable = false)
    private boolean present = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
