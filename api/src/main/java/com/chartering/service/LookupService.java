package com.chartering.service;

import com.chartering.dto.LookupResponse;
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

    @Transactional(readOnly = true)
    public List<LookupResponse> ports() {
        return portRepository.findAll().stream()
                .map(p -> new LookupResponse(p.getId(), p.getName())).toList();
    }

    @Transactional(readOnly = true)
    public List<LookupResponse> tonnageCategories() {
        return tonnageCategoryRepository.findAll().stream()
                .map(t -> new LookupResponse(t.getId(), t.getName())).toList();
    }
}
