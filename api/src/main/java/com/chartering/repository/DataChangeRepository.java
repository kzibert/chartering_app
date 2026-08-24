package com.chartering.repository;

import com.chartering.model.DataChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

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
}
