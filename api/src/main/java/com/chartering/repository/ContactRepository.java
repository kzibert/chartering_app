package com.chartering.repository;

import com.chartering.model.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ContactRepository
        extends JpaRepository<Contact, Long>, JpaSpecificationExecutor<Contact> {

    /** Main contact first, then oldest first — so "the first one" means the same to user and code. */
    List<Contact> findByCompanyIdOrderByMainDescIdAsc(Long companyId);

    /**
     * Distinct <em>working</em> email contacts belonging to the given companies (used to
     * bulk-collect the owner contacts of a filtered vessel set). person + company are
     * join-fetched so the mapper doesn't N+1. confirmedOnly=true restricts to confirmed
     * emails. Addresses flagged not-working never appear.
     * <p>
     * Ordered main-first within each company, so callers wanting a single address per
     * company can simply take the first row of each group.
     */
    @Query("""
            select c from Contact c
              left join fetch c.person
              join fetch c.company comp
            where c.contactKind = 'email'
              and comp.id in :companyIds
              and c.working = true
              and (:confirmedOnly = false or c.confirmed = true)
              and (:includeBanned = true or c.banned = false)
            order by comp.id, c.main desc, c.id
            """)
    List<Contact> findEmailContactsByCompanyIds(
            @Param("companyIds") Collection<Long> companyIds,
            @Param("confirmedOnly") boolean confirmedOnly,
            @Param("includeBanned") boolean includeBanned);

    /**
     * Clear the current main of the same kind for a company, so setting a new one cannot
     * trip the unique index. Runs in the caller's transaction, before the new flag is set.
     */
    /**
     * Of the given companies, those that have email addresses but not one that works.
     * Companies with no email at all are absent — "we have no usable address" is a
     * different situation from "every address we have is dead", and only the latter
     * earns the label.
     */
    @Query("""
            select c.company.id from Contact c
            where c.company.id in :companyIds
              and c.contactKind = 'email'
            group by c.company.id
            having sum(case when c.working then 1 else 0 end) = 0
            """)
    List<Long> findCompanyIdsWithoutWorkingEmail(@Param("companyIds") Collection<Long> companyIds);

    /** Lower-cased dead email addresses — the send-time blocklist for campaigns. */
    @Query("select lower(c.contactValue) from Contact c where c.contactKind = 'email' and c.working = false")
    Set<String> findNotWorkingEmailValues();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Contact c set c.main = false
            where c.company.id = :companyId
              and c.contactKind = :kind
              and c.main = true
              and c.id <> :excludeId
            """)
    void clearMain(@Param("companyId") Long companyId,
                   @Param("kind") String kind,
                   @Param("excludeId") Long excludeId);
}
