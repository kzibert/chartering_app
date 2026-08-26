package com.chartering.repository;

import com.chartering.model.VesselExName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface VesselExNameRepository extends JpaRepository<VesselExName, Long> {

    List<VesselExName> findByVesselIdOrderByNameAsc(Long vesselId);

    /**
     * Former names for a page of vessels, fetched in one query so a list of twenty rows
     * costs one round trip rather than twenty.
     */
    @Query("select e from VesselExName e where e.vessel.id in :vesselIds order by e.name")
    List<VesselExName> findByVesselIds(List<Long> vesselIds);

    boolean existsByVesselIdAndNameIgnoreCase(Long vesselId, String name);
}
