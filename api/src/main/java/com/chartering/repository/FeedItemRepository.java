package com.chartering.repository;

import com.chartering.model.FeedItem;
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

    /** Item counts per source for the Sources list, in one query. */
    @Query("select i.source.id, count(i) from FeedItem i group by i.source.id")
    List<Object[]> countBySource();
}
