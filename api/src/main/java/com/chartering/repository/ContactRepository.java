package com.chartering.repository;

import com.chartering.model.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ContactRepository
        extends JpaRepository<Contact, Long>, JpaSpecificationExecutor<Contact> {

    List<Contact> findByCompanyId(Long companyId);
}
