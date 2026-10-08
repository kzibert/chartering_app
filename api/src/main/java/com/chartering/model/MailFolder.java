package com.chartering.model;

import com.chartering.tenancy.Owned;
import com.chartering.tenancy.ViewerResolver;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/**
 * One of the app's own mail folders.
 *
 * <p>Nothing here corresponds to a folder on the mail server. Filing a message moves a row
 * in {@code mail_messages}; the mailbox is opened read-only and never touched. That is the
 * point of the design rather than a limitation of it — a mis-written rule can rearrange
 * this table and nothing else, and the mailbox stays exactly as its owner left it.
 *
 * <p>There is no row for the Inbox. A message with no folder <em>is</em> in the Inbox, which
 * is the same statement as "nothing has filed it yet".
 */
@Getter
@Setter
@Entity
@Table(name = "mail_folders")
@FilterDef(name = Owned.FILTER,
        // On for every session: nobody needs to remember to switch it on. Not applied to loads
        // by id, because a colleague opening a shared-source message lazily loads its folder,
        // and a filtered load would fail; the services ask for a person's own rows by query.
        autoEnabled = true,
        applyToLoadByKey = false,
        defaultCondition = "(:viewer = 0 or owner_user_id = :viewer)",
        parameters = @ParamDef(name = "viewer", type = Long.class, resolver = ViewerResolver.class))
@EntityListeners(Owned.Stamp.class)
@Filter(name = Owned.FILTER)
public class MailFolder implements Owned {

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

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String notes;

    /** Display order in the folder rail; ties break by name. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
