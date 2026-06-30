package com.chartering.service;

import com.chartering.dto.*;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.VesselRepository;
import com.chartering.specification.CompanySpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final VesselRepository vesselRepository;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public PageResponse<CompanyResponse> search(CompanyFilter f, Pageable pageable) {
        Specification<Company> spec = Specification.allOf(
                CompanySpecification.nameContains(f.name()),
                CompanySpecification.cityContains(f.city()),
                CompanySpecification.roleIsTrue("shipowner", f.shipowner()),
                CompanySpecification.roleIsTrue("charterer", f.charterer()),
                CompanySpecification.roleIsTrue("broker", f.broker()),
                CompanySpecification.roleIsTrue("agent", f.agent()),
                CompanySpecification.confirmedEquals(f.confirmed()),
                CompanySpecification.hasRegionId(f.regionId()),
                CompanySpecification.hasPortId(f.portId()),
                CompanySpecification.hasTonnageCategoryId(f.tonnageCategoryId()),
                CompanySpecification.excludeBanned(f.includeBanned()));
        return PageResponse.from(companyRepository.findAll(spec, pageable).map(mapper::toCompanyResponse));
    }

    @Transactional(readOnly = true)
    public CompanyDetailResponse getDetail(Long id) {
        Company c = companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
        List<ContactResponse> contacts = contactRepository.findByCompanyId(id).stream()
                .map(mapper::toContactResponse).toList();
        List<VesselResponse> vessels = vesselRepository.findByOwnerId(id).stream()
                .map(mapper::toVesselResponse).toList();
        return new CompanyDetailResponse(mapper.toCompanyResponse(c), contacts, vessels);
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> getContacts(Long id) {
        requireExists(id);
        return contactRepository.findByCompanyId(id).stream().map(mapper::toContactResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<VesselResponse> getVessels(Long id) {
        requireExists(id);
        return vesselRepository.findByOwnerId(id).stream().map(mapper::toVesselResponse).toList();
    }

    @Transactional
    public CompanyResponse create(CompanyRequest req) {
        Company c = new Company();
        apply(c, req);
        return mapper.toCompanyResponse(companyRepository.save(c));
    }

    @Transactional
    public CompanyResponse update(Long id, CompanyRequest req) {
        Company c = companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
        apply(c, req);
        return mapper.toCompanyResponse(companyRepository.save(c));
    }

    @Transactional
    public void delete(Long id) {
        requireExists(id);
        companyRepository.deleteById(id);
    }

    @Transactional
    public CompanyResponse setConfirmed(Long id, boolean confirmed, ConfirmRequest req) {
        Company c = companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
        c.setConfirmed(confirmed);
        c.setConfirmedAt(confirmed ? OffsetDateTime.now() : null);
        c.setConfirmedBy(confirmed && req != null ? req.getConfirmedBy() : null);
        c.setConfirmNotes(confirmed && req != null ? req.getConfirmNotes() : null);
        return mapper.toCompanyResponse(companyRepository.save(c));
    }

    private void requireExists(Long id) {
        if (!companyRepository.existsById(id)) {
            throw new ResourceNotFoundException("Company", id);
        }
    }

    private void apply(Company c, CompanyRequest req) {
        c.setName(req.getName());
        c.setShipowner(req.isShipowner());
        c.setCharterer(req.isCharterer());
        c.setBroker(req.isBroker());
        c.setAgent(req.isAgent());
        c.setCityName(req.getCityName());
        c.setNotes(req.getNotes());
    }

    public record CompanyFilter(
            String name, String city,
            Boolean shipowner, Boolean charterer, Boolean broker, Boolean agent,
            Boolean confirmed, Long regionId, Long portId, Long tonnageCategoryId,
            boolean includeBanned) {
    }
}
