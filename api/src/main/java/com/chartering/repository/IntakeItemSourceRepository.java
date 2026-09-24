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
            left join fetch s.feedItem f
            left join fetch f.source
            left join fetch s.reportedByCompany
            where s.intakeItem.id = :itemId
            order by s.reportedAt desc, s.id desc
            """)
    List<IntakeItemSource> forItem(Long itemId);

    boolean existsByIntakeItemIdAndParsedEmailId(Long intakeItemId, Long parsedEmailId);

    /**
     * The company questions still waiting that were raised by the email or post a position was
     * read from — only while the position has no reporter, since a named one is answered.
     * Through the sources rather than the item's own parse, because a company question folds
     * every later email from the firm into the first one's item.
     */
    @Query("""
            select distinct s.intakeItem.id from IntakeItemSource s, VesselPosition p
            where p.id = :positionId and p.reportedByCompany is null
              and s.intakeItem.kind = com.chartering.model.IntakeItemKind.COMPANY_DETAILS
              and s.intakeItem.status = com.chartering.model.IntakeItemStatus.PENDING
              and ((s.feedItem is not null and s.feedItem = p.sourceFeedItem)
                or (s.mailMessage is not null and s.mailMessage = p.sourceMailMessage))
            """)
    List<Long> pendingCompanyItemsForPosition(Long positionId);
}
