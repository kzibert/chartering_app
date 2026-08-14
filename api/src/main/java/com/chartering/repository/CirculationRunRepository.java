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
     * the process that owned it is gone, so nothing will ever complete it. Marked ABORTED
     * at startup rather than left to look permanently in flight.
     */
    List<CirculationRun> findByStateAndFinishedAtIsNull(String state);
}
