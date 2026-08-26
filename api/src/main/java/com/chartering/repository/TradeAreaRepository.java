package com.chartering.repository;

import com.chartering.model.TradeArea;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeAreaRepository extends JpaRepository<TradeArea, Long> {

    Optional<TradeArea> findByCodeIgnoreCase(String code);

    /** Dropdown order: the ranges grouped as the market quotes them, ties broken by name. */
    List<TradeArea> findAllByOrderBySortOrderAscNameAsc();
}
