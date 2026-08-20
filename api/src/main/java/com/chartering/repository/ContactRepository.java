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
     * Contacts of a whole page of people in one query — the people search embeds them,
     * and fetching per row would be an N+1. Ordered so each person's list reads the same
     * way as everywhere else: main first, then oldest.
     */
    @Query("""
            select c from Contact c
              left join fetch c.person
              left join fetch c.company
            where c.person.id in :personIds
              and (:includeBanned = true or c.banned = false)
            order by c.person.id, c.main desc, c.id
            """)
    List<Contact> findByPersonIds(@Param("personIds") Collection<Long> personIds,
                                  @Param("includeBanned") boolean includeBanned);

    /**
     * Distinct <em>working</em> email contacts belonging to the given companies (used to
     * bulk-collect the owner contacts of a filtered vessel set). person + company are
     * join-fetched so the mapper doesn't N+1. confirmedOnly=true restricts to confirmed
     * emails. Addresses flagged not-working or not-for-circ never appear.
     * <p>
     * Ordered circ-first then main-first within each company, which is the order the
     * selection rule reads each group in.
     */
    @Query("""
            select c from Contact c
              left join fetch c.person
              join fetch c.company comp
            where c.contactKind = 'email'
              and comp.id in :companyIds
              and c.working = true
              and c.noCirc = false
              and (:confirmedOnly = false or c.confirmed = true)
              and (:includeBanned = true or c.banned = false)
            order by comp.id, c.circ desc, c.main desc, c.id
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

    /**
     * Working email contacts of the given <em>people</em>, for the People tab's bulk add.
     * Companion to {@link #findEmailContactsByCompanyIds}; ordered circ-first then main-first
     * so the selection rule can read each person's group in precedence order.
     */
    @Query("""
            select c from Contact c
              join fetch c.person p
              left join fetch c.company
            where c.contactKind = 'email'
              and p.id in :personIds
              and c.working = true
              and c.noCirc = false
              and (:confirmedOnly = false or c.confirmed = true)
              and (:includeBanned = true or c.banned = false)
            order by p.id, c.circ desc, c.main desc, c.id
            """)
    List<Contact> findEmailContactsByPersonIds(
            @Param("personIds") Collection<Long> personIds,
            @Param("confirmedOnly") boolean confirmedOnly,
            @Param("includeBanned") boolean includeBanned);

    /**
     * Email contacts whose address is one of these, person and company loaded — how a synced
     * message finds the company it came from.
     *
     * <p>Takes a collection rather than one address because it is used both ways: once per
     * message during a sync, and once for a whole re-link pass over thousands of stored
     * messages, where one query per distinct sender would be the entire cost of the pass.
     * Ordered by id so a duplicated address resolves to the same contact every time rather
     * than to whichever row the planner happened to return first.
     */
    @Query("""
            select c from Contact c
              left join fetch c.person
              left join fetch c.company
            where c.contactKind = 'email'
              and lower(c.contactValue) in :addresses
            order by c.id
            """)
    List<Contact> findEmailContactsByAddresses(@Param("addresses") Collection<String> addresses);

    /** Lower-cased dead email addresses — the send-time blocklist for campaigns. */
    @Query("select lower(c.contactValue) from Contact c where c.contactKind = 'email' and c.working = false")
    Set<String> findNotWorkingEmailValues();

    /**
     * Lower-cased addresses flagged "not for circ" — the second send-time blocklist.
     *
     * <p>Kept apart from {@link #findNotWorkingEmailValues()} rather than unioned with it,
     * because the run has to record <em>which</em> reason applied: "we never mailed them,
     * the address is dead" and "we never mailed them, they are off the circular" lead to
     * completely different actions when somebody asks why a broker did not hear from us.
     */
    @Query("select lower(c.contactValue) from Contact c where c.contactKind = 'email' and c.noCirc = true")
    Set<String> findNoCircEmailValues();

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
