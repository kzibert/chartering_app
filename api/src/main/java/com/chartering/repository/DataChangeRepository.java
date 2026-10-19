package com.chartering.repository;

import com.chartering.model.DataChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Read-only in practice. Rows are written by {@code audit/DataChangeWriter} through JDBC
 * during the flush that caused them, so nothing here ever saves one — see that class for
 * why the log cannot go through the persistence context.
 */
public interface DataChangeRepository
        extends JpaRepository<DataChange, Long>, JpaSpecificationExecutor<DataChange> {

    /** Everyone who has ever changed anything, for the history page's filter. */
    @Query("select distinct d.changedBy from DataChange d where d.changedBy is not null order by d.changedBy")
    List<String> findDistinctChangedBy();

    /**
     * How many change rows describe rows of these entity types, on this desk. Read by
     * {@code CompanyDirectoryVersion}, which needs the count as well as the newest id: an id is
     * taken when its insert runs, not when it commits, so a row with a lower id can commit after
     * a higher one has been counted, and only the count moves for it.
     */
    @Query("select count(d) from DataChange d where d.entityType in :types")
    long countForEntityTypes(@Param("types") java.util.Collection<String> types);

    /** The newest such row's id, or 0 when there is none. See {@link #countForEntityTypes}. */
    @Query("select coalesce(max(d.id), 0) from DataChange d where d.entityType in :types")
    long latestIdForEntityTypes(@Param("types") java.util.Collection<String> types);
}
