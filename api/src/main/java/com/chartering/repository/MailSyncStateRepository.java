package com.chartering.repository;

import com.chartering.model.MailSyncState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailSyncStateRepository extends JpaRepository<MailSyncState, Long> {

    /** The viewer's own cursor for a folder (the owner filter scopes the query). */
    java.util.Optional<MailSyncState> findByImapFolder(String imapFolder);
}
