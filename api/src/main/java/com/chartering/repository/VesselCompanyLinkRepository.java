package com.chartering.repository;

import com.chartering.model.VesselCompanyLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VesselCompanyLinkRepository extends JpaRepository<VesselCompanyLink, Long> {

    @Query("""
            select l from VesselCompanyLink l
              join fetch l.company
            where l.vessel.id = :vesselId
            order by l.role, l.company.name
            """)
    List<VesselCompanyLink> findByVesselId(@Param("vesselId") Long vesselId);

    @Query("""
            select l from VesselCompanyLink l
              join fetch l.vessel v
              left join fetch v.owner
            where l.company.id = :companyId
            order by v.name
            """)
    List<VesselCompanyLink> findByCompanyId(@Param("companyId") Long companyId);

    Optional<VesselCompanyLink> findByVesselIdAndCompanyId(Long vesselId, Long companyId);

    void deleteByVesselIdAndCompanyId(Long vesselId, Long companyId);
}
