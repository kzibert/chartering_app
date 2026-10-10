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

    /**
     * Her most recent reading, whatever became of it — what the vessel record shows as
     * "last open".
     *
     * <p>Deliberately not filtered to LIVE. If she has since fixed, where she was last
     * reported free is still the useful answer, and the status travels with it so the
     * screen can say which it is. Ties on the timestamp break by id, the same way the
     * Open Fleet query does, so this can never return two rows for one instant.
     */
    @EntityGraph(attributePaths = {"openPort", "openPort.tradeArea", "openArea", "reportedByCompany"})
    Optional<VesselPosition> findFirstByVesselIdOrderByReportedAtDescIdDesc(Long vesselId);

    long countByStatus(PositionStatus status);

    /**
     * Positions read out of a mailed email whose sender could not be named when they were filed,
     * and whose message the mailbox now places on file. See {@code IntakeService.attributeUnreported}.
     *
     * <p>Only the rows a message can name: {@code Arrival.of(MailMessage)} takes the sender from
     * the message's company and nothing else, so a null company names nothing and the row is
     * left out here rather than loaded (with its body) every tick to be skipped. A join fetch
     * rather than a left join, because the condition on the message is what selects the rows.
     */
    @Query("select p from VesselPosition p join fetch p.sourceMailMessage m left join fetch p.vessel "
            + "where p.reportedByCompany is null and m.company is not null")
    List<VesselPosition> findUnreportedFromMail();

    /**
     * The same for positions read off a board post. Kept as its own query so the reconcile pass
     * can leave it alone while the desk's directory has not changed: a post's signature can only
     * start naming a firm when a company, a person or an address changes.
     */
    @Query("select p from VesselPosition p join fetch p.sourceFeedItem left join fetch p.vessel "
            + "where p.reportedByCompany is null")
    List<VesselPosition> findUnreportedFromPost();
}
