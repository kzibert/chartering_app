package com.chartering.repository;

import com.chartering.model.MailAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailAccountRepository extends JpaRepository<MailAccount, Long> {

    Optional<MailAccount> findByUserId(Long userId);

    List<MailAccount> findByEnabledTrue();
}
