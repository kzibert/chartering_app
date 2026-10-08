package com.chartering.repository;

import com.chartering.model.FeedSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FeedSourceRepository extends JpaRepository<FeedSource, Long> {

    List<FeedSource> findAllByOrderByNameAsc();

    List<FeedSource> findByEnabledTrueOrderByIdAsc();

    Optional<FeedSource> findByUrl(String url);

    /**
     * The boards the caller's desk reads into Intake - the ones the Intake tab lists and the
     * sweep reads for it. The subscription is the desk's (V34); the tenant filter scopes the
     * subquery to the desk on the thread.
     */
    @Query("select s from FeedSource s where exists (select 1 from FeedIntakeSubscription x where x.feedSourceId = s.id) order by s.name")
    List<FeedSource> findReadIntoIntake();

    @Query("select count(s) > 0 from FeedSource s where s.enabled = true and exists (select 1 from FeedIntakeSubscription x where x.feedSourceId = s.id)")
    boolean existsEnabledReadIntoIntake();
}
