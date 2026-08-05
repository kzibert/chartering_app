package com.chartering.repository;

import com.chartering.model.EmailFooter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EmailFooterRepository extends JpaRepository<EmailFooter, Long> {

    List<EmailFooter> findAllByOrderByNameAsc();

    Optional<EmailFooter> findByNameIgnoreCase(String name);

    Optional<EmailFooter> findByDefaultFooterIsTrue();

    /**
     * Demote every other footer. Must be called <em>before</em> the new default is flushed:
     * the partial unique index rejects two rows carrying the flag even momentarily, so
     * demote-then-promote is the only ordering that survives.
     */
    @Modifying(flushAutomatically = true)
    @Query("update EmailFooter f set f.defaultFooter = false where f.defaultFooter = true and f.id <> :keepId")
    int clearDefaultExcept(Long keepId);

    /** Same, for a create where the new row has no id yet. */
    @Modifying(flushAutomatically = true)
    @Query("update EmailFooter f set f.defaultFooter = false where f.defaultFooter = true")
    int clearAllDefaults();
}
