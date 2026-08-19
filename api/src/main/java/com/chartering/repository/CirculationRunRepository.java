package com.chartering.repository;

import com.chartering.model.CirculationRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CirculationRunRepository extends JpaRepository<CirculationRun, Long> {

    /**
     * History, newest first. Deliberately does not touch {@code recipients}: the dropdown
     * needs one line per run, and composedHtml plus a few hundred recipient rows per entry
     * is not something to drag into a list response.
     */
    Page<CirculationRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    /** Recipients join-fetched — the detail view renders all of them at once. */
    @Query("""
            select distinct r from CirculationRun r
              left join fetch r.recipients
            where r.id = :id
            """)
    Optional<CirculationRun> findByIdWithRecipients(@Param("id") Long id);

    /**
     * A run left RUNNING with no finishedAt was interrupted by an API restart mid-send —
     * the process that owned it is gone, so nothing in this one will ever continue it on
     * its own. Reopened as INTERRUPTED at startup: no longer in flight, but still carrying
     * the PENDING rows that make it resumable.
     */
    List<CirculationRun> findByStateAndFinishedAtIsNull(String state);

    /**
     * Runs that still have somebody to send to. A run is resumable whatever ended it —
     * paused by hand, cancelled, aborted on errors, or cut off by an API restart — because
     * the only thing resuming needs is addresses still marked PENDING.
     *
     * <p>{@code state <> 'RUNNING'} excludes the campaign in flight right now. A run left
     * RUNNING by a dead process is reopened as INTERRUPTED at startup, so RUNNING here
     * always means live.
     */
    @Query("""
            select distinct r from CirculationRun r
              join r.recipients p
            where p.status = 'PENDING' and r.state <> 'RUNNING'
            order by r.startedAt desc
            """)
    List<CirculationRun> findResumable();
}
