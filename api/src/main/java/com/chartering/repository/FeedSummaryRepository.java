package com.chartering.repository;

import com.chartering.model.FeedSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FeedSummaryRepository extends JpaRepository<FeedSummary, Long> {

    Page<FeedSummary> findByTopicId(Long topicId, Pageable pageable);

    /** One summary with the items it was written from, for its detail view. */
    @Query("select s from FeedSummary s left join fetch s.items i left join fetch i.source "
            + "where s.id = :id")
    Optional<FeedSummary> findWithItems(@Param("id") Long id);
}
