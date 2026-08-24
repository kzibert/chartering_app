package com.chartering.service;

import com.chartering.dto.*;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.VesselCompanyLinkRepository;
import com.chartering.repository.VesselRepository;
import com.chartering.specification.CompanySpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final VesselRepository vesselRepository;
    private final VesselCompanyLinkRepository linkRepository;
    private final RecipientSelectionService recipientSelection;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public PageResponse<CompanyResponse> search(CompanyFilter f, Pageable pageable) {
        Page<Company> page = companyRepository.findAll(buildSpec(f), pageable);
        // One extra query for the whole page rather than one per row.
        Set<Long> dead = deadEmailCompanyIds(page.getContent().stream().map(Company::getId).toList());
        return PageResponse.from(page.map(c -> mapper.toCompanyResponse(c, dead.contains(c.getId()))));
    }

    /**
     * The addresses to circulate to for a set of companies — the Companies tab's bulk add.
     *
     * <p>Which addresses those are is decided by {@link RecipientSelectionService}: circ-
     * flagged ones if the group has any, else the main one, else all working ones. The rule
     * runs per person, so a company with five flagged people contributes five addresses,
     * and its own switchboard address is one more group on top.
     *
     * @param companyIds    specific companies (the checkbox selection); when empty the whole
     *                      filtered set is used instead, which is the "add all matching" case
     * @param confirmedOnly restrict to confirmed addresses
     */
    @Transactional(readOnly = true)
    public List<ContactResponse> emailContacts(CompanyFilter f, List<Long> companyIds,
                                               boolean confirmedOnly) {
        List<Long> ids = companyIds == null || companyIds.isEmpty()
                ? companyRepository.findAll(buildSpec(f)).stream().map(Company::getId).toList()
                : companyIds;
        if (ids.isEmpty()) return List.of();
        List<Contact> emails = contactRepository.findEmailContactsByCompanyIds(
                ids, confirmedOnly, f.includeBanned());
        return recipientSelection.select(emails).stream()
                .map(mapper::toContactResponse)
                .toList();
    }

    /** Of these companies, the ones whose every email address is flagged not working. */
    private Set<Long> deadEmailCompanyIds(Collection<Long> companyIds) {
        if (companyIds.isEmpty()) return Set.of();
        return Set.copyOf(contactRepository.findCompanyIdsWithoutWorkingEmail(companyIds));
    }

    private boolean hasNoWorkingEmail(Long companyId) {
        return !deadEmailCompanyIds(List.of(companyId)).isEmpty();
    }

    @Transactional(readOnly = true)
    public CompanyDetailResponse getDetail(Long id) {
        Company c = companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
        List<ContactResponse> contacts = contactRepository.findByCompanyIdOrderByMainDescIdAsc(id).stream()
                .map(mapper::toContactResponse).toList();
        List<CompanyVesselResponse> vessels = getVessels(id);
        // The contacts are already loaded here — derive the flag rather than re-query.
        List<ContactResponse> emails = contacts.stream()
                .filter(ct -> "email".equals(ct.contactKind())).toList();
        boolean noWorkingEmail = !emails.isEmpty() && emails.stream().noneMatch(ContactResponse::working);
        return new CompanyDetailResponse(mapper.toCompanyResponse(c, noWorkingEmail), contacts, vessels);
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> getContacts(Long id) {
        requireExists(id);
        return contactRepository.findByCompanyIdOrderByMainDescIdAsc(id).stream().map(mapper::toContactResponse).toList();
    }

    /**
     * Every vessel this company is attached to, owned or brokered, each tagged with the
     * capacity. Owned first — that is the relationship the desk cares about most — then
     * the brokered ones. A company holds one role per vessel, so nothing appears twice.
     */
    @Transactional(readOnly = true)
    public List<CompanyVesselResponse> getVessels(Long id) {
        requireExists(id);
        List<CompanyVesselResponse> owned = vesselRepository.findByOwnerId(id).stream()
                .map(v -> new CompanyVesselResponse(mapper.toVesselResponse(v), VesselService.ROLE_OWNER))
                .toList();
        List<CompanyVesselResponse> brokered = linkRepository.findByCompanyId(id).stream()
                .map(l -> new CompanyVesselResponse(mapper.toVesselResponse(l.getVessel()), l.getRole()))
                .toList();
        return Stream.concat(owned.stream(), brokered.stream()).toList();
    }

    @Transactional
    public CompanyResponse create(CompanyRequest req) {
        Company c = new Company();
        apply(c, req);
        return mapper.toCompanyResponse(companyRepository.save(c), hasNoWorkingEmail(c.getId()));
    }

    @Transactional
    public CompanyResponse update(Long id, CompanyRequest req) {
        Company c = companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
        apply(c, req);
        return mapper.toCompanyResponse(companyRepository.save(c), hasNoWorkingEmail(c.getId()));
    }

    @Transactional
    public void delete(Long id) {
        requireExists(id);
        companyRepository.deleteById(id);
    }

    @Transactional
    public CompanyResponse setBanned(Long id, boolean banned) {
        Company c = companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
        c.setBanned(banned);
        return mapper.toCompanyResponse(companyRepository.save(c), hasNoWorkingEmail(c.getId()));
    }

    @Transactional
    public CompanyResponse setConfirmed(Long id, boolean confirmed, ConfirmRequest req) {
        Company c = companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
        c.setConfirmed(confirmed);
        c.setConfirmedAt(confirmed ? OffsetDateTime.now() : null);
        c.setConfirmedBy(confirmed && req != null ? req.getConfirmedBy() : null);
        c.setConfirmNotes(confirmed && req != null ? req.getConfirmNotes() : null);
        return mapper.toCompanyResponse(companyRepository.save(c), hasNoWorkingEmail(c.getId()));
    }

    /** Shared by the paged search and the bulk collect, so both see the same result set. */
    private Specification<Company> buildSpec(CompanyFilter f) {
        return Specification.allOf(
                CompanySpecification.nameContains(f.name()),
                CompanySpecification.cityContains(f.city()),
                CompanySpecification.hasPersonNamed(f.personName()),
                CompanySpecification.roleIsTrue("shipowner", f.shipowner()),
                CompanySpecification.roleIsTrue("charterer", f.charterer()),
                CompanySpecification.roleIsTrue("broker", f.broker()),
                CompanySpecification.roleIsTrue("agent", f.agent()),
                CompanySpecification.confirmedEquals(f.confirmed()),
                CompanySpecification.hasRegionId(f.regionId()),
                CompanySpecification.hasPortId(f.portId()),
                CompanySpecification.hasTonnageCategoryId(f.tonnageCategoryId()),
                CompanySpecification.excludeBanned(f.includeBanned()),
                CompanySpecification.noWorkingEmail(f.noWorkingEmail()),
                CompanySpecification.legacyEquals(f.legacy()));
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
        c.setSolo(req.isSolo());
        c.setCityName(req.getCityName());
        c.setCountry(req.getCountry());
        c.setWebsite(req.getWebsite());
        c.setNotes(req.getNotes());
    }

    public record CompanyFilter(
            String name, String city, String personName,
            Boolean shipowner, Boolean charterer, Boolean broker, Boolean agent,
            Boolean confirmed, Long regionId, Long portId, Long tonnageCategoryId,
            boolean includeBanned, Boolean legacy, Boolean noWorkingEmail) {
    }
}
