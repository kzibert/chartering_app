package com.chartering.repository;

import com.chartering.model.VesselLookup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface VesselLookupRepository extends JpaRepository<VesselLookup, Long> {

    Optional<VesselLookup> findByIntakeItemId(Long intakeItemId);

    boolean existsByIntakeItemId(Long intakeItemId);

    /**
     * The latest search asked about a hull from her own record rather than from a review item.
     *
     * <p><b>Why the {@code intakeItemId is null} half matters.</b> Both kinds of row carry a
     * {@code vessel_id} once they have been applied, so "the lookups for this vessel" would
     * also return the ones raised by an email — and the vessel screen would show a search
     * prompted by a circular read three weeks ago as though somebody had just run it. The item
     * rows belong to the item drawer, where the email that caused them is on the page.
     *
     * <p>Newest first and one row taken, because a search supersedes the last one: the screen
     * asks "what does the source say about her now". The older rows stay as the provenance of
     * figures already applied, which is what {@code ix_vessel_lookups_vessel} is indexed for.
     */
    Optional<VesselLookup> findTopByVesselIdAndIntakeItemIsNullOrderByFetchedAtDesc(Long vesselId);

    /**
     * Review items that are still waiting and have never been looked up.
     *
     * <p>The trigger for the whole feature: a hull is searched for because somebody has to
     * answer a question about her, not because an email mentioned her. That keeps the number
     * of outside requests to the size of the queue rather than the size of the mailbox —
     * eighty positions in one circular produce a handful of questions, and only those are
     * worth somebody else's bandwidth.
     *
     * <p>Oldest first: the queue is worked from the top, so the item most likely to be opened
     * next is the one most worth having an answer ready for.
     */
    @Query("""
            select i.id from IntakeItem i
            where i.status = com.chartering.model.IntakeItemStatus.PENDING
              and i.kind in (com.chartering.model.IntakeItemKind.NEW_VESSEL,
                             com.chartering.model.IntakeItemKind.VESSEL_FIELDS)
              and not exists (select 1 from VesselLookup l where l.intakeItem = i)
            order by i.id asc
            """)
    List<Long> pendingWithoutLookup(org.springframework.data.domain.Pageable pageable);

    long countByStatus(String status);
}
