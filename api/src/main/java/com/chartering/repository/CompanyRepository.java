package com.chartering.repository;

import com.chartering.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CompanyRepository
        extends JpaRepository<Company, Long>, JpaSpecificationExecutor<Company> {
}
