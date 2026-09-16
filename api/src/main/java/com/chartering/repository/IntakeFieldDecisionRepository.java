package com.chartering.repository;

import com.chartering.model.IntakeFieldDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface IntakeFieldDecisionRepository extends JpaRepository<IntakeFieldDecision, Long> {

    /**
     * Everything already settled about one hull, matched in memory afterwards.
     *
     * <p>One indexed read rather than a query per field: a hull has a handful of these at
     * most, and the caller is holding a diff of two or three rows it needs to test each of.
     * The company is compared in Java too, because the null case ("the sync could not put an
     * address to a firm") is a value here and not a wildcard, and expressing that in JPQL
     * costs more than it saves.
     */
    @Query("select d from IntakeFieldDecision d left join fetch d.reportedByCompany where d.vesselId = ?1")
    List<IntakeFieldDecision> forVessel(Long vesselId);
}
