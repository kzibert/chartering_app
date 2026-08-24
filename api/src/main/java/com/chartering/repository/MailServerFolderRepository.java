package com.chartering.repository;

import com.chartering.model.MailServerFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailServerFolderRepository extends JpaRepository<MailServerFolder, String> {

    /** The rail's order: Inbox, then the owner's folders, then the system ones. */
    List<MailServerFolder> findAllByOrderBySortOrderAscFullNameAsc();

    /** Only what the server still lists — what the sync loop is allowed to read. */
    List<MailServerFolder> findByPresentTrueOrderBySortOrderAscFullNameAsc();

    /**
     * A folder by its IMAP SPECIAL-USE role, which is the only reliable way to ask for one:
     * this mailbox calls its Sent folder "Отправленные", and matching on the English word
     * would find nothing at all.
     */
    Optional<MailServerFolder> findFirstBySpecialUseAndPresentTrue(String specialUse);
}
