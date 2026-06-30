package com.chartering.clean.repository;

import com.chartering.clean.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CompanyRepository
        extends JpaRepository<Company, Long>, JpaSpecificationExecutor<Company> {
}
