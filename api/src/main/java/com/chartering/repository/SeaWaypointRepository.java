package com.chartering.repository;

import com.chartering.model.SeaWaypoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SeaWaypointRepository extends JpaRepository<SeaWaypoint, Long> {

    Optional<SeaWaypoint> findByCode(String code);
}
