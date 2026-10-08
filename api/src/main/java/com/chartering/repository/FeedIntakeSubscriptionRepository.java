package com.chartering.repository;

import com.chartering.model.FeedIntakeSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** The caller's desk's subscriptions - the tenant filter scopes every query here. */
public interface FeedIntakeSubscriptionRepository extends JpaRepository<FeedIntakeSubscription, Long> {

    Optional<FeedIntakeSubscription> findByFeedSourceId(Long feedSourceId);

    @Query("select s.feedSourceId from FeedIntakeSubscription s")
    List<Long> findSourceIds();
}
