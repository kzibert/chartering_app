package com.chartering.repository;

import com.chartering.model.IntakeVesselAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface IntakeVesselAliasRepository extends JpaRepository<IntakeVesselAlias, Long> {

    /**
     * The hull this firm means by this name, if it has told us.
     *
     * <p>Asked only after the two exact tiers have failed — see {@code IntakeResolver}. An
     * alias must never widen the search, or a correspondent's habit would start overruling a
     * ship's own name.
     */
    @Query("""
            select a from IntakeVesselAlias a
            where a.reportedByCompany.id = ?1 and a.nameKey = ?2
            """)
    Optional<IntakeVesselAlias> find(Long companyId, String nameKey);

    /** Every firm's name for one hull, for her own record to show what it answers to. */
    @Query("""
            select a from IntakeVesselAlias a
            left join fetch a.reportedByCompany
            where a.vesselId = ?1
            order by a.createdAt desc
            """)
    List<IntakeVesselAlias> forVessel(Long vesselId);
}
