package com.chartering.repository;

import com.chartering.model.CargoVesselMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CargoVesselMatchRepository extends JpaRepository<CargoVesselMatch, Long> {

    Optional<CargoVesselMatch> findByCargoIdAndVesselId(Long cargoId, Long vesselId);

    /** Every standing decision on one cargo, read once and applied to the scored list. */
    @Query("select m from CargoVesselMatch m where m.cargo.id = :cargoId")
    List<CargoVesselMatch> findByCargoId(Long cargoId);

    @Query("select m from CargoVesselMatch m join fetch m.cargo where m.vessel.id = :vesselId")
    List<CargoVesselMatch> findByVesselId(Long vesselId);
}
