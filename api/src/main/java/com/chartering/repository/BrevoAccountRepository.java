package com.chartering.repository;

import com.chartering.model.BrevoAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BrevoAccountRepository extends JpaRepository<BrevoAccount, Long> {

    Optional<BrevoAccount> findByUserId(Long userId);
}
