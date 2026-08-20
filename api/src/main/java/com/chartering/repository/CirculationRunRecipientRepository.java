package com.chartering.repository;

import com.chartering.model.CirculationRunRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface CirculationRunRecipientRepository extends JpaRepository<CirculationRunRecipient, Long> {

    /**
     * Record one address's outcome as the run passes it. A targeted update rather than a
     * load-modify-save: this fires once per message from the campaign worker thread, and
     * the row is never read back in between.
     */
    @Modifying
    @Query("""
            update CirculationRunRecipient r
               set r.status = :status,
                   r.attempts = :attempts,
                   r.error = :error,
                   r.sentAt = :sentAt
             where r.id = :id
            """)
    void recordOutcome(@Param("id") Long id,
                       @Param("status") String status,
                       @Param("attempts") int attempts,
                       @Param("error") String error,
                       @Param("sentAt") LocalDateTime sentAt);

    /**
     * How many addresses were actually mailed inside a window, across every run.
     *
     * <p>Counted from {@code sentAt} rather than from the run-level counters: a run that
     * started last night, or was resumed this morning, must land each message on the day it
     * really went out. Half-open ({@code >= from}, {@code < until}) so midnight belongs to
     * exactly one day — which {@code between} would not give.
     */
    @Query("""
            select count(r) from CirculationRunRecipient r
            where r.status = :status
              and r.sentAt >= :from
              and r.sentAt < :until
            """)
    int countSentBetween(@Param("status") String status,
                         @Param("from") LocalDateTime from,
                         @Param("until") LocalDateTime until);

    /**
     * How many circulations those messages came from. Counted over the same rows as
     * {@link #countSentBetween}, so the two numbers always describe the same set — runs
     * <em>started</em> in the window would not, since a circulation opened last night and
     * resumed this morning delivers today while belonging to yesterday.
     */
    @Query("""
            select count(distinct r.run.id) from CirculationRunRecipient r
            where r.status = :status
              and r.sentAt >= :from
              and r.sentAt < :until
            """)
    int countRunsSendingBetween(@Param("status") String status,
                                @Param("from") LocalDateTime from,
                                @Param("until") LocalDateTime until);

    /**
     * How many of a run's addresses ended up in one status.
     *
     * <p>The run-level counters are written once, when the run closes — an UPDATE per
     * message would double the write cost of a send for a number nobody reads mid-run. A
     * run killed with its process never got that write, so its counters are rebuilt from
     * these rows, which <em>are</em> written as each message goes out.
     */
    int countByRunIdAndStatus(Long runId, String status);
}
