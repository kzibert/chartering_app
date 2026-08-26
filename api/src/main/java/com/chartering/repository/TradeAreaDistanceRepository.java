package com.chartering.repository;

import com.chartering.model.TradeAreaDistance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeAreaDistanceRepository
        extends JpaRepository<TradeAreaDistance, TradeAreaDistance.Key> {
}
