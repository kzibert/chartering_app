package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable set of circular recipients, prepared in advance.
 *
 * <p>Exactly one row has {@code draft = true}: the unnamed "current list" that the
 * Companies/Vessels/People tabs add into. Saving it under a name copies it to a new row —
 * the draft itself is never renamed, so there is always somewhere to collect into.
 */
@Getter
@Setter
@Entity
@Table(name = "circulation_lists")
public class CirculationList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The desk this row belongs to. Written and filtered by Hibernate - see TenantIdentifierResolver. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /**
     * Whose current list this is - set on the draft only. Saved lists are the desk's prepared
     * documents and have no owner; the draft is one person's scratch pad.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** Null only on the draft row (see ux_circulation_lists_name). */
    private String name;

    @Column(name = "is_draft", nullable = false)
    private boolean draft = false;

    @Column(columnDefinition = "text")
    private String notes;

    /**
     * Owned one-to-many: entries have no life of their own, and a list is always loaded
     * with them. orphanRemoval lets the service rewrite a list by mutating the collection.
     */
    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc, id asc")
    private List<CirculationListEntry> entries = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    /** Keeps both sides of the association consistent, which orphanRemoval depends on. */
    public void addEntry(CirculationListEntry e) {
        e.setList(this);
        e.setSortOrder(entries.size());
        entries.add(e);
    }
}
