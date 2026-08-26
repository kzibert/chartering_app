package com.chartering.service;

import com.chartering.dto.*;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.Vessel;
import com.chartering.model.VesselCompanyLink;
import com.chartering.model.VesselExName;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.VesselCompanyLinkRepository;
import com.chartering.repository.VesselExNameRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VesselService {

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_EXCLUSIVE = "exclusive_broker";
    public static final String ROLE_BROKER = "broker";

    private final VesselRepository vesselRepository;
    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final VesselCompanyLinkRepository linkRepository;
    private final VesselExNameRepository exNameRepository;
    private final RecipientSelectionService recipientSelection;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public PageResponse<VesselResponse> search(VesselFilter f, Pageable pageable) {
        Page<Vessel> page = vesselRepository.findAll(buildSpec(f), pageable);
        Map<Long, List<VesselExNameResponse>> exNames = exNamesFor(page.getContent());
        return PageResponse.from(page.map(
                v -> mapper.toVesselResponse(v, exNames.getOrDefault(v.getId(), List.of()))));
    }

    /**
     * Former names for a whole page of vessels, in one query.
     *
     * <p>The alternative — letting the mapper walk a lazy collection per row — is twenty
     * queries behind a twenty-row page, and the ex-names are wanted on every row rather than
     * on the few somebody expanded. Vessels with none are simply absent from the map.
     */
    private Map<Long, List<VesselExNameResponse>> exNamesFor(List<Vessel> vessels) {
        if (vessels.isEmpty()) return Map.of();
        List<Long> ids = vessels.stream().map(Vessel::getId).toList();
        return exNameRepository.findByVesselIds(ids).stream()
                .collect(Collectors.groupingBy(e -> e.getVessel().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(mapper::toVesselExNameResponse, Collectors.toList())));
    }

    // ------------------------------------------------------------ former names

    @Transactional(readOnly = true)
    public List<VesselExNameResponse> exNames(Long vesselId) {
        if (!vesselRepository.existsById(vesselId)) {
            throw new ResourceNotFoundException("Vessel", vesselId);
        }
        return exNameRepository.findByVesselIdOrderByNameAsc(vesselId).stream()
                .map(mapper::toVesselExNameResponse).toList();
    }

    /**
     * Record a name this vessel used to carry.
     *
     * <p>The duplicate check is here as well as on the unique index, because the index would
     * answer with a constraint violation and a 500. One hull carrying the same former name
     * twice is not an error worth a stack trace — several circulars saying the same true
     * thing is the normal case.
     */
    @Transactional
    public VesselExNameResponse addExName(Long vesselId, VesselExNameRequest req) {
        Vessel v = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", vesselId));
        String name = req.getName().trim();
        if (exNameRepository.existsByVesselIdAndNameIgnoreCase(vesselId, name)) {
            throw new IllegalArgumentException(
                    v.getName() + " already lists \"" + name + "\" as a former name");
        }
        VesselExName e = new VesselExName();
        e.setVessel(v);
        e.setName(name);
        e.setSource(VesselExName.SOURCE_MANUAL);
        e.setRenamedAt(req.getRenamedAt());
        e.setNotes(req.getNotes());
        return mapper.toVesselExNameResponse(exNameRepository.save(e));
    }

    @Transactional
    public void removeExName(Long vesselId, Long exNameId) {
        VesselExName e = exNameRepository.findById(exNameId)
                .orElseThrow(() -> new ResourceNotFoundException("Former name", exNameId));
        // Checked rather than trusted: the id arrives in a URL nested under a vessel, and
        // deleting one ship's history through another ship's path would be a quiet bug.
        if (!e.getVessel().getId().equals(vesselId)) {
            throw new ResourceNotFoundException("Former name", exNameId);
        }
        exNameRepository.delete(e);
    }

    /**
     * The owner-company addresses to circulate to, for a set of vessels.
     *
     * <p>Which addresses those are is decided by {@link RecipientSelectionService}: circ-
     * flagged ones if the owner has any, else the main one, else all working ones — applied
     * per person, so one flagged contact does not silence their colleagues.
     *
     * @param vesselIds     specific vessels (the checkbox selection); when empty the whole
     *                      filtered set is used instead, which is the "add all matching" case
     * @param confirmedOnly restrict to confirmed addresses
     */
    @Transactional(readOnly = true)
    public List<ContactResponse> ownerEmailContacts(VesselFilter f, List<Long> vesselIds,
                                                    boolean confirmedOnly) {
        Set<Long> ownerIds = ownerIds(f, vesselIds);
        if (ownerIds.isEmpty()) return List.of();
        List<Contact> emails = contactRepository.findEmailContactsByCompanyIds(
                ownerIds, confirmedOnly, f.includeBanned());
        return recipientSelection.select(emails).stream()
                .map(mapper::toContactResponse)
                .toList();
    }

    /**
     * Owner companies of the vessels in scope. An explicit id list wins over the filter:
     * ticking three rows means those three, whatever the search box still holds.
     */
    private Set<Long> ownerIds(VesselFilter f, List<Long> vesselIds) {
        List<Vessel> vessels = vesselIds == null || vesselIds.isEmpty()
                ? vesselRepository.findAll(buildSpec(f))
                : vesselRepository.findAllById(vesselIds);
        return vessels.stream()
                .map(Vessel::getOwner)
                .filter(Objects::nonNull)
                .map(Company::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Specification<Vessel> buildSpec(VesselFilter f) {
        return Specification.allOf(
                VesselSpecification.nameContains(f.name()),
                VesselSpecification.imoEquals(f.imoNumber()),
                // DWT and DWCC are alternative statements of the same thing and are rarely
                // both on file — 2353 vessels have a DWT and no DWCC, 285 the reverse — so
                // filling both boxes has to mean "either", not "both". Same for grain/bale.
                // The two pairs stay AND'd with each other and with everything else.
                //
                // anyOf ignores a null member, so filling only one box of a pair leaves just
                // that restriction: one figure entered means vessels known to satisfy it,
                // not vessels merely not known to breach it.
                Specification.anyOf(
                        VesselSpecification.recordedRange("deadweightTonnage", f.minDwt(), f.maxDwt()),
                        VesselSpecification.recordedRange("deadweightCargoCapacity", f.minDwcc(), f.maxDwcc())),
                Specification.anyOf(
                        VesselSpecification.recordedRange("grainCapacityM3", f.minGrain(), f.maxGrain()),
                        VesselSpecification.recordedRange("baleCapacityM3", f.minBale(), f.maxBale())),
                VesselSpecification.recordedRange("maximumDraft", null, f.maxDraft()),
                VesselSpecification.yearFrom(f.yearFrom()),
                VesselSpecification.gearedEquals(f.geared()),
                VesselSpecification.vesselTypeIn(f.vesselType()),
                VesselSpecification.flagIn(f.flag()),
                VesselSpecification.companyIdEquals(f.companyId()),
                VesselSpecification.companyNameContains(f.companyName()),
                VesselSpecification.confirmedEquals(f.confirmed()),
                VesselSpecification.excludeBanned(f.includeBanned()),
                VesselSpecification.legacyEquals(f.legacy()));
    }

    @Transactional(readOnly = true)
    public VesselDetailResponse getDetail(Long id) {
        Vessel v = vesselRepository.findWithOwnerById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", id));
        Company owner = v.getOwner();
        List<ContactResponse> ownerContacts = owner == null ? List.of()
                : contactRepository.findByCompanyIdOrderByMainDescIdAsc(owner.getId()).stream()
                        .map(mapper::toContactResponse).toList();
        // Derived from the contacts already loaded above rather than a second query.
        List<ContactResponse> ownerEmails = ownerContacts.stream()
                .filter(ct -> "email".equals(ct.contactKind())).toList();
        boolean ownerNoWorkingEmail =
                !ownerEmails.isEmpty() && ownerEmails.stream().noneMatch(ContactResponse::working);
        CompanyResponse ownerDto = owner != null
                ? mapper.toCompanyResponse(owner, ownerNoWorkingEmail) : null;
        return new VesselDetailResponse(
                mapper.toVesselResponse(v, exNames(id)), ownerDto, ownerContacts, links(id));
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
        // With the former names, because the form that sent this update is showing them and
        // takes what comes back as the record's new state. Returning the bare vessel would
        // empty that list on screen after every save.
        return mapper.toVesselResponse(vesselRepository.save(v), exNames(id));
    }

    @Transactional
    public void delete(Long id) {
        if (!vesselRepository.existsById(id)) {
            throw new ResourceNotFoundException("Vessel", id);
        }
        vesselRepository.deleteById(id);
    }

    /** Owner (from vessels.owner_id) first, then the broker links. */
    @Transactional(readOnly = true)
    public List<VesselCompanyLinkResponse> links(Long vesselId) {
        Vessel v = vesselRepository.findWithOwnerById(vesselId)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", vesselId));

        List<VesselCompanyLinkResponse> out = new ArrayList<>();
        Company owner = v.getOwner();
        if (owner != null) {
            out.add(new VesselCompanyLinkResponse(
                    owner.getId(), owner.getName(), owner.getCityName(), ROLE_OWNER, null));
        }
        linkRepository.findByVesselId(vesselId).forEach(l -> out.add(new VesselCompanyLinkResponse(
                l.getCompany().getId(), l.getCompany().getName(), l.getCompany().getCityName(),
                l.getRole(), l.getNotes())));
        return out;
    }

    /**
     * Attach a company to a vessel in one capacity, replacing whatever role it held before —
     * a company appears once per vessel.
     *
     * 'owner' writes vessels.owner_id (and drops any broker row the company had, so the two
     * stores can never both claim it); the broker roles write the link table and clear
     * owner_id if this company was the owner. Promoting a new owner displaces the old one,
     * which is what one-owner-per-vessel means.
     */
    @Transactional
    public List<VesselCompanyLinkResponse> setLink(Long vesselId, Long companyId, String role, String notes) {
        if (!ROLE_OWNER.equals(role) && !ROLE_EXCLUSIVE.equals(role) && !ROLE_BROKER.equals(role)) {
            throw new IllegalArgumentException(
                    "role must be one of: " + ROLE_OWNER + ", " + ROLE_EXCLUSIVE + ", " + ROLE_BROKER);
        }
        Vessel v = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", vesselId));
        Company c = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));

        linkRepository.deleteByVesselIdAndCompanyId(vesselId, companyId);
        linkRepository.flush();

        if (ROLE_OWNER.equals(role)) {
            v.setOwner(c);
            vesselRepository.save(v);
        } else {
            if (v.getOwner() != null && v.getOwner().getId().equals(companyId)) {
                v.setOwner(null);
                vesselRepository.save(v);
            }
            // Only one exclusive broker per vessel; demote the incumbent rather than
            // failing on the unique index.
            if (ROLE_EXCLUSIVE.equals(role)) {
                linkRepository.findByVesselId(vesselId).stream()
                        .filter(l -> ROLE_EXCLUSIVE.equals(l.getRole()))
                        .forEach(l -> l.setRole(ROLE_BROKER));
                linkRepository.flush();
            }
            VesselCompanyLink link = new VesselCompanyLink();
            link.setVessel(v);
            link.setCompany(c);
            link.setRole(role);
            link.setNotes(notes);
            linkRepository.save(link);
        }
        return links(vesselId);
    }

    /** Detach a company from a vessel, whichever capacity it was in. */
    @Transactional
    public List<VesselCompanyLinkResponse> removeLink(Long vesselId, Long companyId) {
        Vessel v = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", vesselId));
        if (v.getOwner() != null && v.getOwner().getId().equals(companyId)) {
            v.setOwner(null);
            vesselRepository.save(v);
        }
        linkRepository.deleteByVesselIdAndCompanyId(vesselId, companyId);
        return links(vesselId);
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
        v.setGeared(req.getGeared());
        v.setGearDescription(req.getGearDescription());
        v.setHolds(req.getHolds());
        v.setHatches(req.getHatches());
        v.setGrainFitted(req.getGrainFitted());
        v.setTimberFitted(req.getTimberFitted());
        v.setImoFitted(req.getImoFitted());
        v.setIceClass(req.getIceClass());
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
            /** Only a maximum: the question is always "will it fit", never "is it deep enough". */
            BigDecimal maxDraft,
            /** Oldest acceptable build year; matches that year and younger. */
            Integer yearFrom,
            /** Geared, gearless, or null for "do not narrow on it". */
            Boolean geared,
            List<String> vesselType, List<String> flag,
            Long companyId, String companyName, Boolean confirmed,
            boolean includeBanned, Boolean legacy) {
    }
}
