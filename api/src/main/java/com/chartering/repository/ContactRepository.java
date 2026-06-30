package com.chartering.repository;

import com.chartering.model.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ContactRepository
        extends JpaRepository<Contact, Long>, JpaSpecificationExecutor<Contact> {

    List<Contact> findByCompanyId(Long companyId);

    /**
     * Distinct email contacts belonging to the given companies (used to bulk-collect the
     * owner contacts of a filtered vessel set). person + company are join-fetched so the
     * mapper doesn't N+1. confirmedOnly=true restricts to confirmed emails.
     */
    @Query("""
            select c from Contact c
              left join fetch c.person
              join fetch c.company comp
            where c.contactKind = 'email'
              and comp.id in :companyIds
              and (:confirmedOnly = false or c.confirmed = true)
            order by comp.id, c.contactValue
            """)
    List<Contact> findEmailContactsByCompanyIds(
            @Param("companyIds") Collection<Long> companyIds,
            @Param("confirmedOnly") boolean confirmedOnly);
}
