package com.chartering.repository;

import com.chartering.model.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {

    List<EmailTemplate> findAllByOrderByNameAsc();

    /** Name uniqueness is case-insensitive (matching the ux_email_templates_name index). */
    Optional<EmailTemplate> findByNameIgnoreCase(String name);
}
