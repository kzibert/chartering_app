package com.chartering.repository;

import com.chartering.model.SeaLeg;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeaLegRepository extends JpaRepository<SeaLeg, SeaLeg.Key> {
}
