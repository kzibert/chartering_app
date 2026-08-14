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
}
