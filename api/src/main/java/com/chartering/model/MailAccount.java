package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.OffsetDateTime;

/**
 * One person's own mailbox: where to read their mail and how to send as them.
 *
 * <p>One identity for both directions - the address is the IMAP and SMTP login and the From of
 * everything sent - because a provider refuses a From its login does not own, and two fields
 * that must always agree are two fields free to disagree.
 *
 * <p>The password is stored encrypted ({@code CredentialCipher}) and never leaves the server.
 * Not audited: the change log would be the one place a ciphertext was copied to, and "the
 * mailbox password changed" is not a fact anybody reviews history for.
 *
 * <p>The server's own mailbox ({@code IMAP_*}, {@code MAIL_*}) is not a row here. It belongs
 * to one account by configuration ({@code MAILBOX_OWNER}) and keeps its credentials in the
 * environment, which is where an existing deployment already has them.
 */
@Getter
@Setter
@Entity
@Table(name = "mail_accounts")
public class MailAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The desk this row belongs to. Written and filtered by Hibernate - see TenantIdentifierResolver. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "email_address", nullable = false, length = 255)
    private String emailAddress;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "imap_host", nullable = false, length = 255)
    private String imapHost;

    @Column(name = "imap_port", nullable = false)
    private int imapPort = 993;

    @Column(name = "imap_ssl", nullable = false)
    private boolean imapSsl = true;

    @Column(name = "smtp_host", nullable = false, length = 255)
    private String smtpHost;

    @Column(name = "smtp_port", nullable = false)
    private int smtpPort = 465;

    @Column(name = "password_enc", nullable = false, columnDefinition = "text")
    private String passwordEnc;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
