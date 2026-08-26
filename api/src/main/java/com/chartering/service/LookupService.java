package com.chartering.service;

import com.chartering.dto.LookupResponse;
import com.chartering.dto.PortLookupResponse;
import com.chartering.dto.TradeAreaResponse;
import com.chartering.model.TradeArea;
import com.chartering.repository.PortRepository;
import com.chartering.repository.RegionRepository;
import com.chartering.repository.TonnageCategoryRepository;
import com.chartering.repository.VesselRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LookupService {

    private final VesselRepository vesselRepository;
    private final RegionRepository regionRepository;
    private final PortRepository portRepository;
    private final TonnageCategoryRepository tonnageCategoryRepository;
    private final TradeAreaGraph tradeAreas;

    @Transactional(readOnly = true)
    public List<String> vesselTypes() {
        return vesselRepository.findDistinctVesselTypes();
    }

    @Transactional(readOnly = true)
    public List<String> flags() {
        return vesselRepository.findDistinctFlags();
    }

    @Transactional(readOnly = true)
    public List<LookupResponse> regions() {
        return regionRepository.findAll().stream()
                .map(r -> new LookupResponse(r.getId(), r.getName())).toList();
    }

    /**
     * Ports with the water each sits on, sorted by name.
     *
     * <p>The area rides along so a cargo or position form can show "Salerno (W.Med)" while
     * somebody is choosing - the consequence of the choice for matching is then visible at
     * the moment it is made, rather than after saving.
     */
    @Transactional(readOnly = true)
    public List<PortLookupResponse> ports() {
        return portRepository.findAll().stream()
                .map(p -> {
                    TradeArea area = p.getTradeArea();
                    return new PortLookupResponse(p.getId(), p.getName(),
                            area != null ? area.getId() : null,
                            area != null ? area.getCode() : null);
                })
                .sorted(java.util.Comparator.comparing(PortLookupResponse::name,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The trade-area vocabulary, in dropdown order with each area's aliases.
     *
     * <p>Read through {@link TradeAreaGraph} rather than the repository: the graph already
     * holds the whole thing in memory for matching, and a second path to the same rows is a
     * second thing to keep consistent.
     */
    @Transactional(readOnly = true)
    public List<TradeAreaResponse> tradeAreas() {
        return tradeAreas.all().stream()
                .map(a -> new TradeAreaResponse(
                        a.id(), a.code(), a.name(), a.parentId(), a.parentCode(),
                        a.sortOrder(), a.notes(), tradeAreas.aliasesOf(a.id())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LookupResponse> tonnageCategories() {
        return tonnageCategoryRepository.findAll().stream()
                .map(t -> new LookupResponse(t.getId(), t.getName())).toList();
    }
}
