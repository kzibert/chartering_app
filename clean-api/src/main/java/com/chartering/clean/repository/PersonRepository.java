package com.chartering.clean.repository;

import com.chartering.clean.model.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface PersonRepository
        extends JpaRepository<Person, Long>, JpaSpecificationExecutor<Person> {

    List<Person> findByCompanyId(Long companyId);
}
