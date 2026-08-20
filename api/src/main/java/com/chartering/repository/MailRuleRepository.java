package com.chartering.repository;

import com.chartering.model.MailRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MailRuleRepository extends JpaRepository<MailRule, Long> {

    /**
     * Every rule in evaluation order, conditions and target folder already loaded.
     *
     * <p>Fetched in one query on purpose: rules are evaluated against every message of a
     * sync batch, and lazily loading each rule's conditions inside that loop would be an
     * N+1 per message rather than per rule. {@code distinct} because the join to the
     * conditions multiplies the rule rows.
     */
    @Query("""
            select distinct r from MailRule r
              left join fetch r.conditions
              join fetch r.folder
            where r.enabled = true
            order by r.sortOrder, r.id
            """)
    List<MailRule> findEnabledForEvaluation();

    /** Every rule, enabled or not, for the rules editor. */
    @Query("""
            select distinct r from MailRule r
              left join fetch r.conditions
              join fetch r.folder
            order by r.sortOrder, r.id
            """)
    List<MailRule> findAllForDisplay();

    @Query("select r from MailRule r where lower(r.name) = lower(:name)")
    Optional<MailRule> findByNameIgnoringCase(@Param("name") String name);

    /** Highest order in use, so a new rule lands at the end rather than fighting for first. */
    @Query("select coalesce(max(r.sortOrder), 0) from MailRule r")
    int maxSortOrder();
}
