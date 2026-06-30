package com.chartering.repository;

import com.chartering.model.Vessel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface VesselRepository
        extends JpaRepository<Vessel, Long>, JpaSpecificationExecutor<Vessel> {

    @EntityGraph(attributePaths = "owner")
    Page<Vessel> findAll(Specification<Vessel> spec, Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    Optional<Vessel> findWithOwnerById(Long id);

    List<Vessel> findByOwnerId(Long ownerId);

    @org.springframework.data.jpa.repository.Query(
            "select distinct v.vesselType from Vessel v where v.vesselType is not null order by v.vesselType")
    List<String> findDistinctVesselTypes();

    @org.springframework.data.jpa.repository.Query(
            "select distinct v.flag from Vessel v where v.flag is not null order by v.flag")
    List<String> findDistinctFlags();
}
