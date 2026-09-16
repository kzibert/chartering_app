package com.chartering.repository;

import com.chartering.model.FeedTopic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeedTopicRepository extends JpaRepository<FeedTopic, Long> {

    List<FeedTopic> findAllByOrderBySortOrderAscIdAsc();

    List<FeedTopic> findBySelectedTrueOrderBySortOrderAscIdAsc();

    Optional<FeedTopic> findByNameIgnoreCase(String name);
}
