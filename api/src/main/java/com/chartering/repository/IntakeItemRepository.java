package com.chartering.repository;

import com.chartering.model.IntakeItem;
import com.chartering.model.IntakeItemKind;
import com.chartering.model.IntakeItemStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface IntakeItemRepository
        extends JpaRepository<IntakeItem, Long>, JpaSpecificationExecutor<IntakeItem> {

    @Override
    @EntityGraph(attributePaths = {"parsedEmail", "parsedEmail.mailMessage"})
    Page<IntakeItem> findAll(org.springframework.data.jpa.domain.Specification<IntakeItem> spec,
                             Pageable pageable);

    @EntityGraph(attributePaths = {"parsedEmail", "parsedEmail.mailMessage"})
    Optional<IntakeItem> findWithEmailById(Long id);

    long countByStatus(IntakeItemStatus status);

    @Query("select i.kind, count(i) from IntakeItem i where i.status = ?1 group by i.kind")
    List<Object[]> countByKind(IntakeItemStatus status);

    List<IntakeItem> findByParsedEmailIdOrderByIdAsc(Long parsedEmailId);

    /**
     * A pending item already asking this question about this vessel.
     *
     * <p>Why it is asked at all: a broker's list arrives every morning, and every morning it
     * disagrees with the database about the same deadweight. Without this, one unanswered
     * disagreement becomes thirty queue rows in a month and the queue stops being read —
     * which is the failure mode that matters, because a review screen nobody opens is worse
     * than no review screen. Answering it once, either way, is what clears it: an accepted
     * item has written the value so the next list agrees, and a rejected one is re-raised
     * only if the incoming figures have moved since (see {@code IntakeService}).
     */
    @Query("""
            select i from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = ?1 and i.vesselId = ?2
            order by i.id desc
            """)
    List<IntakeItem> pendingForVessel(IntakeItemKind kind, Long vesselId);

    /**
     * Every waiting item of one kind, with its parse and message loaded.
     *
     * <p>For the pass that re-reads {@code NEW_VESSEL} items against what a lookup has since
     * found: a hull whose number turns out to be on file is not a new ship, and the item is
     * rewritten rather than left asking the wrong question. Small by construction — the queue
     * is a screen's worth, not a table scan — and the fetch is because converting reads the
     * message to file her position.
     */
    @Query("""
            select distinct i from IntakeItem i
            left join fetch i.parsedEmail p
            left join fetch p.mailMessage
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = ?1
            order by i.id asc
            """)
    List<IntakeItem> pendingByKind(IntakeItemKind kind);

    /** The same question about a hull that is not on file yet, matched on the name as spelled. */
    @Query("""
            select i from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind = com.chartering.model.IntakeItemKind.NEW_VESSEL
              and lower(i.subjectLabel) = lower(?1)
            order by i.id desc
            """)
    List<IntakeItem> pendingNewVessel(String name);
}
