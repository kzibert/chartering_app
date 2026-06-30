package com.chartering.service;

import com.chartering.dto.*;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
import com.chartering.model.Vessel;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.VesselRepository;
import com.chartering.specification.VesselSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VesselService {

    private final VesselRepository vesselRepository;
    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public PageResponse<VesselResponse> search(VesselFilter f, Pageable pageable) {
        return PageResponse.from(
                vesselRepository.findAll(buildSpec(f), pageable).map(mapper::toVesselResponse));
    }

    /**
     * Email contacts of the owner companies of every vessel matching {@code f}.
     * confirmedOnly=true keeps only confirmed emails. Distinct by contact; an owner with
     * no contacts (or vessels with no owner) simply contributes nothing.
     */
    @Transactional(readOnly = true)
    public List<ContactResponse> ownerEmailContacts(VesselFilter f, boolean confirmedOnly) {
        Set<Long> ownerIds = vesselRepository.findAll(buildSpec(f)).stream()
                .map(Vessel::getOwner)
                .filter(Objects::nonNull)
                .map(Company::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ownerIds.isEmpty()) return List.of();
        return contactRepository.findEmailContactsByCompanyIds(ownerIds, confirmedOnly, f.includeBanned()).stream()
                .map(mapper::toContactResponse)
                .toList();
    }

    private Specification<Vessel> buildSpec(VesselFilter f) {
        return Specification.allOf(
                VesselSpecification.nameContains(f.name()),
                VesselSpecification.imoEquals(f.imoNumber()),
                VesselSpecification.numberRange("deadweightTonnage", f.minDwt(), f.maxDwt()),
                VesselSpecification.numberRange("deadweightCargoCapacity", f.minDwcc(), f.maxDwcc()),
                VesselSpecification.numberRange("grainCapacityM3", f.minGrain(), f.maxGrain()),
                VesselSpecification.numberRange("baleCapacityM3", f.minBale(), f.maxBale()),
                VesselSpecification.numberRange("maximumDraft", f.minDraft(), f.maxDraft()),
                VesselSpecification.yearRange(f.minYear(), f.maxYear()),
                VesselSpecification.vesselTypeIn(f.vesselType()),
                VesselSpecification.flagIn(f.flag()),
                VesselSpecification.ownerIdEquals(f.ownerId()),
                VesselSpecification.ownerNameContains(f.ownerName()),
                VesselSpecification.confirmedEquals(f.confirmed()),
                VesselSpecification.excludeBanned(f.includeBanned()));
    }

    @Transactional(readOnly = true)
    public VesselDetailResponse getDetail(Long id) {
        Vessel v = vesselRepository.findWithOwnerById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", id));
        Company owner = v.getOwner();
        CompanyResponse ownerDto = owner != null ? mapper.toCompanyResponse(owner) : null;
        List<ContactResponse> ownerContacts = owner == null ? List.of()
                : contactRepository.findByCompanyId(owner.getId()).stream()
                        .map(mapper::toContactResponse).toList();
        return new VesselDetailResponse(mapper.toVesselResponse(v), ownerDto, ownerContacts);
    }

    @Transactional
    public VesselResponse create(VesselRequest req) {
        Vessel v = new Vessel();
        apply(v, req);
        return mapper.toVesselResponse(vesselRepository.save(v));
    }

    @Transactional
    public VesselResponse update(Long id, VesselRequest req) {
        Vessel v = vesselRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", id));
        apply(v, req);
        return mapper.toVesselResponse(vesselRepository.save(v));
    }

    @Transactional
    public void delete(Long id) {
        if (!vesselRepository.existsById(id)) {
            throw new ResourceNotFoundException("Vessel", id);
        }
        vesselRepository.deleteById(id);
    }

    @Transactional
    public VesselResponse setBanned(Long id, boolean banned) {
        Vessel v = vesselRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", id));
        v.setBanned(banned);
        return mapper.toVesselResponse(vesselRepository.save(v));
    }

    @Transactional
    public VesselResponse setConfirmed(Long id, boolean confirmed, ConfirmRequest req) {
        Vessel v = vesselRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", id));
        v.setConfirmed(confirmed);
        v.setConfirmedAt(confirmed ? OffsetDateTime.now() : null);
        v.setConfirmedBy(confirmed && req != null ? req.getConfirmedBy() : null);
        v.setConfirmNotes(confirmed && req != null ? req.getConfirmNotes() : null);
        return mapper.toVesselResponse(vesselRepository.save(v));
    }

    private void apply(Vessel v, VesselRequest req) {
        v.setName(req.getName());
        v.setImoNumber(req.getImoNumber());
        v.setDeadweightTonnage(req.getDeadweightTonnage());
        v.setDeadweightCargoCapacity(req.getDeadweightCargoCapacity());
        v.setGrainCapacityM3(req.getGrainCapacityM3());
        v.setBaleCapacityM3(req.getBaleCapacityM3());
        v.setMaximumDraft(req.getMaximumDraft());
        v.setYearBuilt(req.getYearBuilt());
        v.setVesselType(req.getVesselType());
        v.setFlag(req.getFlag());
        v.setNotes(req.getNotes());
        v.setOwner(resolveOwner(req.getOwnerId()));
    }

    private Company resolveOwner(Long ownerId) {
        if (ownerId == null) return null;
        return companyRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", ownerId));
    }

    /** Filter holder so the controller stays readable with many optional params. */
    public record VesselFilter(
            String name, String imoNumber,
            BigDecimal minDwt, BigDecimal maxDwt,
            BigDecimal minDwcc, BigDecimal maxDwcc,
            BigDecimal minGrain, BigDecimal maxGrain,
            BigDecimal minBale, BigDecimal maxBale,
            BigDecimal minDraft, BigDecimal maxDraft,
            Integer minYear, Integer maxYear,
            List<String> vesselType, List<String> flag,
            Long ownerId, String ownerName, Boolean confirmed,
            boolean includeBanned) {
    }
}
