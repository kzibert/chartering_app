package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.OffsetDateTime;

/**
 * One person's own Brevo account: the API key their circulars (and, under
 * {@code MAIL_REPLY_PROVIDER=BREVO}, their replies) go out through, and the verified sender
 * they go out as.
 *
 * <p>Per person for the reason a mailbox is: circulars are sent per person, and a key is a
 * daily allowance and a sending reputation - spending a colleague's, or another desk's, is
 * spending something that is not yours. The key is stored encrypted ({@code CredentialCipher})
 * and never leaves the server; {@code keyHint} is what the screen shows instead.
 *
 * <p>The environment's {@code BREVO_API_KEY} is not a row here. It belongs to the account that
 * owns the environment's mailbox, as the mailbox credentials do.
 */
@Getter
@Setter
@Entity
@Table(name = "brevo_accounts")
public class BrevoAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The desk this row belongs to. Written and filtered by Hibernate - see TenantIdentifierResolver. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "api_key_enc", nullable = false, columnDefinition = "text")
    private String apiKeyEnc;

    @Column(name = "key_hint", nullable = false, length = 8)
    private String keyHint;

    @Column(name = "sender_address", length = 255)
    private String senderAddress;

    @Column(name = "sender_name", length = 200)
    private String senderName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
