package com.chartering.repository;

import com.chartering.model.FeedItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface FeedItemRepository
        extends JpaRepository<FeedItem, Long>, JpaSpecificationExecutor<FeedItem> {

    /** Whether one item is stored — asked by readers deciding whether to open another page. */
    boolean existsBySourceIdAndExternalId(Long sourceId, String externalId);

    /**
     * Which of these ids one source already holds. One query per fetch rather than one per
     * item — and it is what makes a second fetch of an unchanged page write nothing.
     */
    @Query("select i.externalId from FeedItem i "
            + "where i.source.id = :sourceId and i.externalId in :ids")
    List<String> findExistingExternalIds(@Param("sourceId") Long sourceId,
                                         @Param("ids") Collection<String> ids);

    /**
     * Everything an enabled source published inside the window, newest first.
     *
     * <p>An item whose page gave no date counts from when it was fetched: a pasted circular with
     * no date line is still this week's circular if it turned up this week.
     */
    @Query("select i from FeedItem i join fetch i.source s "
            + "where s.enabled = true "
            + "and coalesce(i.publishedAt, i.fetchedAt) >= :since "
            + "order by coalesce(i.publishedAt, i.fetchedAt) desc")
    List<FeedItem> findForSummary(@Param("since") LocalDateTime since);

    /**
     * The intake sweep's queue: posts off the boards marked {@code into_intake} that nobody
     * has read yet, newest first.
     *
     * <p>{@code parsed_emails} is the dedupe here exactly as it is for mail — an item with a
     * row of any status is read, and the absence of one is the whole of "not read". So a
     * board fetched every hour presents only what it has actually added, and running the
     * sweep twice costs nothing.
     *
     * <p>Newest first and inside the lookback for the mail queue's reasons, which bite harder
     * here: a board is a standing page, so the first sweep after switching a source on would
     * otherwise read every entry the page still shows, and a three-week-old position is a
     * ship that has sailed. An item whose page gave no date counts from when it was fetched.
     *
     * <p>The source must be enabled as well as marked: switching a source off is how a board
     * stops being read without losing what it has already collected, and a queue that ignored
     * that would go on parsing a source somebody had just turned off.
     */
    @Query("""
            select i from FeedItem i join fetch i.source s
            where s.enabled = true and s.intoIntake = true
              and i.text is not null and i.text <> ''
              and coalesce(i.publishedAt, i.fetchedAt) >= :since
              and not exists (select 1 from ParsedEmail p where p.feedItem = i)
            order by coalesce(i.publishedAt, i.fetchedAt) desc
            """)
    List<FeedItem> unparsedForIntake(@Param("since") LocalDateTime since, Pageable pageable);

    /** How many posts are waiting to be read, for the Intake tab's header. */
    @Query("""
            select count(i) from FeedItem i
            where i.source.enabled = true and i.source.intoIntake = true
              and i.text is not null and i.text <> ''
              and coalesce(i.publishedAt, i.fetchedAt) >= :since
              and not exists (select 1 from ParsedEmail p where p.feedItem = i)
            """)
    long countUnparsedForIntake(@Param("since") LocalDateTime since);

    /** Item counts per source for the Sources list, in one query. */
    @Query("select i.source.id, count(i) from FeedItem i group by i.source.id")
    List<Object[]> countBySource();
}
