package com.chartering.repository;

import com.chartering.model.IntakeItemSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface IntakeItemSourceRepository extends JpaRepository<IntakeItemSource, Long> {

    /**
     * Every arrival behind one item, newest first.
     *
     * <p>Newest first because the item's figures are the newest reading — that is the order the
     * drawer lists them in, so the email the numbers came from is the one at the top.
     *
     * <p>Fetched rather than lazy: the drawer prints the sender's name and the message subject
     * for each, and a list of five would otherwise be five round trips per row read.
     */
    @Query("""
            select s from IntakeItemSource s
            left join fetch s.mailMessage m
            left join fetch s.reportedByCompany
            where s.intakeItem.id = :itemId
            order by s.reportedAt desc, s.id desc
            """)
    List<IntakeItemSource> forItem(Long itemId);

    boolean existsByIntakeItemIdAndParsedEmailId(Long intakeItemId, Long parsedEmailId);
}
