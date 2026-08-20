package com.chartering.repository;

import com.chartering.model.MailFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MailFolderRepository extends JpaRepository<MailFolder, Long> {

    /** Rail order: the explicit order first, then name, so untouched folders still sort sanely. */
    List<MailFolder> findAllByOrderBySortOrderAscNameAsc();

    /** Names are the identity in the UI, so uniqueness is checked the way the index enforces it. */
    @Query("select f from MailFolder f where lower(f.name) = lower(:name)")
    Optional<MailFolder> findByNameIgnoringCase(@Param("name") String name);
}
