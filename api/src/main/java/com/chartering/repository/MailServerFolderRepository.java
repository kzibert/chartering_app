package com.chartering.repository;

import com.chartering.model.MailServerFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MailServerFolderRepository extends JpaRepository<MailServerFolder, String> {

    /** The rail's order: Inbox, then the owner's folders, then the system ones. */
    List<MailServerFolder> findAllByOrderBySortOrderAscFullNameAsc();

    /** Only what the server still lists — what the sync loop is allowed to read. */
    List<MailServerFolder> findByPresentTrueOrderBySortOrderAscFullNameAsc();
}
