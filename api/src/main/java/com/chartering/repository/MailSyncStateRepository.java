package com.chartering.repository;

import com.chartering.model.MailSyncState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailSyncStateRepository extends JpaRepository<MailSyncState, String> {
}
