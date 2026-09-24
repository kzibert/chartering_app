package com.chartering.repository;

import com.chartering.model.VesselFieldReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface VesselFieldReportRepository extends JpaRepository<VesselFieldReport, Long> {

    /**
     * Everything every firm has reported about one hull, matched in memory afterwards.
     *
     * <p>The decisions' shape and for the same reason: one indexed read per vessel paragraph,
     * where the caller then tests a dozen fields against it. A hull a busy desk hears about
     * daily carries a few dozen of these, because a row is a distinct statement and not an
     * arrival.
     */
    @Query("select r from VesselFieldReport r left join fetch r.reportedByCompany where r.vesselId = ?1")
    List<VesselFieldReport> forVessel(Long vesselId);
}
