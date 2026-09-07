package com.chartering.repository;

import com.chartering.model.PortAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PortAliasRepository extends JpaRepository<PortAlias, Long> {

    /**
     * Every alias with its port attached, for the one caller that wants the lot.
     *
     * <p>{@code PortDirectory} loads the whole vocabulary into memory on refresh, so the
     * join is done once rather than per alias — the same shape, and for the same reason, as
     * {@code TradeAreaAliasRepository.findAllWithArea}.
     */
    @Query("select a from PortAlias a join fetch a.port")
    List<PortAlias> findAllWithPort();
}
