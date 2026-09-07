package com.chartering.repository;

import com.chartering.model.Vessel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface VesselRepository
        extends JpaRepository<Vessel, Long>, JpaSpecificationExecutor<Vessel> {

    @EntityGraph(attributePaths = "owner")
    Page<Vessel> findAll(Specification<Vessel> spec, Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    Optional<Vessel> findWithOwnerById(Long id);

    List<Vessel> findByOwnerId(Long ownerId);

    // ---- what the email parser matches a position against, in order of authority ----

    /**
     * By IMO number — the only identifier that survives a rename, and the reason the
     * extraction asks the model for one at all.
     *
     * <p><b>The column is unique where it is set</b> — {@code ux_vessels_imo} is a partial
     * unique index over the non-null values — so this can return at most one row, and two
     * hulls sharing a number is something the database refuses rather than something to guard
     * against. The list shape is kept anyway because it costs nothing and makes the empty
     * case explicit at the call site, and because the callers already branch on the count.
     *
     * <p>That index is also what makes writing an IMO onto the wrong hull fail loudly instead
     * of silently: see {@code VesselLookupService#applyToVessel}, where a number already held
     * by another vessel is the strongest evidence there is that the two are one ship.
     */
    @Query("select v from Vessel v where v.imoNumber is not null and trim(v.imoNumber) = ?1")
    List<Vessel> findByImoNumber(String imoNumber);

    /**
     * By the name she carries now, or any she used to.
     *
     * <p>Exact and case-insensitive, not the substring match the search box uses: a search
     * is a person narrowing a list and can afford to be generous, while this decides on its
     * own whether a position belongs to a hull already on file. "ATLANTIC" matching
     * "ATLANTIC BREEZE" would file one owner's position against another owner's ship, and
     * nothing downstream would ever question it.
     *
     * <p>The argument is lowercased here and must arrive already trimmed: HQL's
     * {@code trim()} will not take a bare parameter as its operand, so the stored side is
     * trimmed in the query and the incoming side in Java.
     */
    @Query("""
            select v from Vessel v
            where lower(trim(v.name)) = lower(?1)
               or exists (select 1 from VesselExName e
                          where e.vessel = v and lower(trim(e.name)) = lower(?1))
            """)
    List<Vessel> findByExactName(String name);

    /**
     * Hulls that might be the one an email is describing — the third tier, and the only
     * inexact one.
     *
     * <p>Deliberately generous where the two above are exact, because it feeds suggestions
     * on a review screen rather than an automatic decision: its job is to put the right ship
     * in front of a person, not to be right on its own. Either arm is enough, and the two
     * catch different failures. A spelling — "LADY LEILA" for LADY LEYLA — is what the name
     * arm finds; a ship renamed outright, with nothing on file to bridge the two names, is
     * only findable by her particulars.
     *
     * <p>An arm the email gave nothing for is switched off by bounds that match nothing —
     * an impossible deadweight range, a name pattern no row carries — rather than by a null
     * test. Postgres cannot infer the type of a bare parameter in {@code (? is not null and
     * ...)} and refuses the statement, so the null test is not available even where it would
     * read better. With neither arm the caller does not run this at all, which is the right
     * answer: there is nothing to be reminded of.
     */
    @Query("""
            select v from Vessel v
            where (v.deadweightTonnage between :dwtMin and :dwtMax)
               or (lower(v.name) like :namePrefix)
            """)
    List<Vessel> findSimilar(@Param("dwtMin") BigDecimal dwtMin,
                             @Param("dwtMax") BigDecimal dwtMax,
                             @Param("namePrefix") String namePrefix,
                             Pageable pageable);

    @Query("select distinct v.vesselType from Vessel v where v.vesselType is not null order by v.vesselType")
    List<String> findDistinctVesselTypes();

    @Query("select distinct v.flag from Vessel v where v.flag is not null order by v.flag")
    List<String> findDistinctFlags();
}
