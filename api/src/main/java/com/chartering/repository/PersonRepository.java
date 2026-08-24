package com.chartering.repository;

import com.chartering.model.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

// Listing goes through PersonSpecification so company and name filters compose.
public interface PersonRepository
        extends JpaRepository<Person, Long>, JpaSpecificationExecutor<Person> {

    /**
     * Everybody already on file at any of these companies, for the importer to match its
     * rows against. Whole companies rather than name-by-name: the import needs to decide
     * "is this a person we know?" for every row, and the answer is only meaningful within
     * one company — two Tom Cardons at two firms are two people.
     */
    @Query("select p from Person p where p.company.id in :companyIds")
    List<Person> findByCompanyIds(@Param("companyIds") Collection<Long> companyIds);
}
