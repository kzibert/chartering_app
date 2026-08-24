package com.chartering.repository;

import com.chartering.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CompanyRepository
        extends JpaRepository<Company, Long>, JpaSpecificationExecutor<Company> {

    /**
     * Companies whose name matches any of these, ignoring case and surrounding space.
     *
     * <p>One query for the whole import rather than one per row: a file naming forty
     * companies would otherwise be forty round trips before a preview could be drawn, and
     * the preview is the screen the user is waiting on.
     */
    @Query("select c from Company c where lower(trim(c.name)) in :names")
    List<Company> findByLowercaseNames(@Param("names") Collection<String> names);
}
