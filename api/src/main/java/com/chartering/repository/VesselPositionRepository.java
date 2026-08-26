package com.chartering.repository;

import com.chartering.model.PositionStatus;
import com.chartering.model.VesselPosition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface VesselPositionRepository
        extends JpaRepository<VesselPosition, Long>, JpaSpecificationExecutor<VesselPosition> {

    @EntityGraph(attributePaths = {"vessel", "vessel.owner", "openPort", "openArea", "reportedByCompany"})
    Page<VesselPosition> findAll(Specification<VesselPosition> spec, Pageable pageable);

    @EntityGraph(attributePaths = {
            "vessel", "vessel.owner", "openPort", "openPort.tradeArea", "openArea",
            "reportedByCompany", "reportedByPerson", "sourceMailMessage"})
    Optional<VesselPosition> findWithDetailById(Long id);

    /** A vessel's own reporting history, newest first, for the record drawer. */
    @EntityGraph(attributePaths = {"openPort", "openArea", "reportedByCompany", "reportedByPerson"})
    List<VesselPosition> findByVesselIdOrderByReportedAtDesc(Long vesselId);

    /**
     * The newest live position per vessel — what Open Fleet lists and Match reads.
     *
     * <p>Written as "no newer live row exists for this vessel" rather than as a window
     * function, because that phrasing is what the {@code (vessel_id, reported_at DESC)}
     * index actually serves. Ties on the timestamp break by id, so two positions reported in
     * the same instant still yield exactly one row.
     */
    @Query("select p from VesselPosition p "
            + "join fetch p.vessel v left join fetch v.owner "
            + "left join fetch p.openPort op left join fetch op.tradeArea "
            + "left join fetch p.openArea "
            + "left join fetch p.reportedByCompany "
            + "where p.status = :status "
            + "and not exists (select 1 from VesselPosition n where n.vessel = p.vessel "
            + "    and n.status = :status "
            + "    and (n.reportedAt > p.reportedAt "
            + "         or (n.reportedAt = p.reportedAt and n.id > p.id)))")
    List<VesselPosition> findCurrentPositions(PositionStatus status);

    long countByStatus(PositionStatus status);
}
