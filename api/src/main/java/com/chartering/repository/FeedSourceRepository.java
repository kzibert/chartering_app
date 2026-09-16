package com.chartering.repository;

import com.chartering.model.FeedSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeedSourceRepository extends JpaRepository<FeedSource, Long> {

    List<FeedSource> findAllByOrderByNameAsc();

    List<FeedSource> findByEnabledTrueOrderByIdAsc();

    Optional<FeedSource> findByUrl(String url);
}
