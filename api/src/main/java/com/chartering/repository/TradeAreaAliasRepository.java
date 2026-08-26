package com.chartering.repository;

import com.chartering.model.TradeAreaAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TradeAreaAliasRepository extends JpaRepository<TradeAreaAlias, Long> {

    /**
     * Every alias with the area it points at, in one query. There are around 120 of them and
     * they change roughly never, so the whole table is read once into
     * {@code TradeAreaGraph} rather than queried per lookup.
     */
    @Query("select a from TradeAreaAlias a join fetch a.tradeArea")
    List<TradeAreaAlias> findAllWithArea();
}
