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

    long countByStatus(CargoStatus status);
}
