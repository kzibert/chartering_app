package com.chartering.repository;

import com.chartering.model.TradeAreaAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TradeAreaAliasRepository extends JpaRepository<TradeAreaAlias, Long> {

    /**
     * Every alias with the area it points at, in one query. There are around 120 of them and
     * they change roughly never, so the whole table is read once into
     * {@code TradeAreaGraph} rather than queried per lookup.
     */
    /** The market's own spellings, read by every desk. */
    @Query("select a from TradeAreaAlias a join fetch a.tradeArea where a.tenantId is null")
    List<TradeAreaAlias> findGlobalWithArea();

    /** One desk's additions. */
    @Query("select a from TradeAreaAlias a join fetch a.tradeArea where a.tenantId = :tenantId order by a.alias")
    List<TradeAreaAlias> findForTenantWithArea(@Param("tenantId") Long tenantId);
}
