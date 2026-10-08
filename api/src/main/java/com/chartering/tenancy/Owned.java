package com.chartering.tenancy;

import jakarta.persistence.PrePersist;

/**
 * A row that belongs to one person inside a desk: their mailbox, its folders and rules, the
 * replies they sent.
 *
 * <p>The owner is written here, on insert, from whoever the thread is working for - the
 * logged-in account, or the account whose mailbox a sync is reading - the way Hibernate writes
 * the desk. A row written with nobody on the thread fails rather than belonging to no one.
 *
 * <p>Which rows a person <em>sees</em> is {@link #FILTER}: on for every session, it limits
 * these entities' queries to the viewer's own rows. Work bound to no person (a desk-wide sweep)
 * sees the whole desk, still within it.
 */
public interface Owned {

    /** The Hibernate filter, declared on {@code MailFolder}. */
    String FILTER = "ownedByViewer";

    Long getOwnerUserId();

    void setOwnerUserId(Long ownerUserId);

    /** The entity listener that stamps the owner. */
    class Stamp {
        @PrePersist
        void stamp(Object entity) {
            if (entity instanceof Owned owned && owned.getOwnerUserId() == null) {
                owned.setOwnerUserId(TenantContext.requireUser());
            }
        }
    }
}
