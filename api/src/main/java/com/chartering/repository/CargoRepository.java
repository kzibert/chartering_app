package com.chartering.repository;

import com.chartering.model.Cargo;
import com.chartering.model.CargoStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CargoRepository extends JpaRepository<Cargo, Long>, JpaSpecificationExecutor<Cargo> {

    /**
     * Every association the list row prints, fetched with the page. A cargo row shows its
     * load and discharge points and who it came from, and each of those lazily loaded per
     * row is the select-per-row problem the rest of this app avoids by the same means.
     */
    @EntityGraph(attributePaths = {
            "loadPort", "loadArea", "dischargePort", "dischargeArea",
            "chartererCompany", "brokerCompany", "brokerPerson"})
    Page<Cargo> findAll(Specification<Cargo> spec, Pageable pageable);

    @EntityGraph(attributePaths = {
            "loadPort", "loadPort.tradeArea", "loadArea",
            "dischargePort", "dischargePort.tradeArea", "dischargeArea",
            "chartererCompany", "brokerCompany", "brokerPerson", "sourceMailMessage"})
    Optional<Cargo> findWithDetailById(Long id);

    /**
     * The cargoes Match works from. The load port's own area is fetched too: it is the first
     * thing the location test reads, and falling back to a lazy load per cargo inside the
     * scoring loop would turn one query into one per cargo.
     */
    @Query("select c from Cargo c "
            + "left join fetch c.loadPort lp left join fetch lp.tradeArea "
            + "left join fetch c.loadArea "
            + "left join fetch c.dischargePort dp left join fetch dp.tradeArea "
            + "left join fetch c.dischargeArea "
            + "where c.status in :statuses order by c.id desc")
    List<Cargo> findForMatching(List<CargoStatus> statuses);

    /**
     * Cargoes a freshly read one might be a second sighting of.
     *
     * <p>Narrowed by the one thing a duplicate can never differ on — it is still worth
     * working — and by nothing else. Matching the commodity in SQL was the first attempt and
     * it was too brittle against real readings: the same enquiry arrived as "Wheat" from one
     * broker and "wheat moloo" from another, because the model had folded the tolerance into
     * the commodity, and an equality test never brought the two together. So the commodity is
     * compared in Java on a shared significant word, along with the laycan, the load point
     * and the quantity, which are ranges and nulls rather than equalities anyway.
     *
     * <p>Reading every live cargo to do it is the same bargain {@link #findForMatching} makes
     * and for the same reason: a desk's live cargo list is tens of rows, not thousands, and
     * one query beats a page of criteria that still would not express the rule.
     *
     * <p>The load point comes with them because the comparison reads it on every candidate.
     */
    @Query("select c from Cargo c "
            + "left join fetch c.loadPort lp left join fetch lp.tradeArea "
            + "left join fetch c.loadArea "
            + "where c.status in :statuses "
            + "order by c.id desc")
    List<Cargo> findLive(List<CargoStatus> statuses);

    long countByStatus(CargoStatus status);
}
