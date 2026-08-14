package com.chartering.repository;

import com.chartering.model.CirculationList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CirculationListRepository extends JpaRepository<CirculationList, Long> {

    Optional<CirculationList> findByDraftTrue();

    Optional<CirculationList> findByNameIgnoreCase(String name);

    /** Saved lists only — the draft is fetched by its own accessor, never picked from a list. */
    List<CirculationList> findByDraftFalseOrderByNameAsc();

    /**
     * Entry counts for every list in one query. The picker shows "Weekly owners (214)" for
     * each list, and loading whole entry collections just to call size() on them would be
     * a textbook N+1 over lists that can each hold hundreds of rows.
     */
    @Query("""
            select l.id, count(e.id) from CirculationList l
              left join l.entries e
            group by l.id
            """)
    List<Object[]> countEntriesPerList();
}
