package com.chartering.service;

import com.chartering.dto.PageResponse;
import com.chartering.dto.VesselExNameResponse;
import com.chartering.dto.VesselPositionRequest;
import com.chartering.dto.VesselPositionResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.PositionStatus;
import com.chartering.model.Vessel;
import com.chartering.model.VesselPosition;
import com.chartering.repository.*;
import com.chartering.specification.VesselPositionSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VesselPositionService {

    private final VesselPositionRepository positionRepository;
    private final VesselRepository vesselRepository;
    private final VesselExNameRepository exNameRepository;
    private final PortRepository portRepository;
    private final TradeAreaRepository tradeAreaRepository;
    private final CompanyRepository companyRepository;
    private final PersonRepository personRepository;
    private final MailMessageRepository mailMessageRepository;
    private final DtoMapper mapper;

    public record PositionFilter(String vesselName,
                                 Long vesselId,
                                 List<PositionStatus> status,
                                 Long openAreaId,
                                 LocalDate openFrom,
                                 LocalDate openTo,
                                 Long reportedByCompanyId,
                                 Integer reportedWithinDays,
                                 BigDecimal minSize,
                                 BigDecimal maxSize,
                                 Boolean geared,
                                 boolean includeBanned,
                                 /** Newest live row per vessel only — what Open Fleet means. */
                                 boolean current) {
    }

    @Transactional(readOnly = true)
    public PageResponse<VesselPositionResponse> search(PositionFilter f, Pageable pageable) {
        Page<VesselPosition> page = positionRepository.findAll(buildSpec(f), pageable);
        return PageResponse.from(page.map(withExNames(page.getContent())));
    }

    private Specification<VesselPosition> buildSpec(PositionFilter f) {
        return Specification.allOf(
                VesselPositionSpecification.currentOnly(f.current()),
                // A status filter alongside current=true is contradictory rather than
                // additive - current already means LIVE - so the caller asking for one turns
                // the other off. Documented on the endpoint; enforced here so the two cannot
                // silently AND into an empty page.
                f.current() ? null : VesselPositionSpecification.statusIn(f.status()),
                VesselPositionSpecification.vesselNameContains(f.vesselName()),
                VesselPositionSpecification.vesselIdEquals(f.vesselId()),
                VesselPositionSpecification.openAreaEquals(f.openAreaId()),
                VesselPositionSpecification.openOverlaps(f.openFrom(), f.openTo()),
                VesselPositionSpecification.reportedByCompany(f.reportedByCompanyId()),
                VesselPositionSpecification.reportedWithinDays(f.reportedWithinDays()),
                VesselPositionSpecification.vesselSizeBetween(f.minSize(), f.maxSize()),
                VesselPositionSpecification.vesselGeared(f.geared()),
                VesselPositionSpecification.excludeBanned(f.includeBanned()));
    }

    /** A vessel's whole reporting history, newest first. */
    @Transactional(readOnly = true)
    public List<VesselPositionResponse> history(Long vesselId) {
        if (!vesselRepository.existsById(vesselId)) {
            throw new ResourceNotFoundException("Vessel", vesselId);
        }
        List<VesselPosition> rows = positionRepository.findByVesselIdOrderByReportedAtDesc(vesselId);
        return rows.stream().map(withExNames(rows)).toList();
    }

    @Transactional(readOnly = true)
    public VesselPositionResponse get(Long id) {
        VesselPosition p = load(id);
        return mapper.toVesselPositionResponse(p, exNamesOf(p.getVessel().getId()));
    }

    /**
     * Record a position.
     *
     * <p>A new live report supersedes the reporter's own previous live one for that vessel,
     * and nobody else's. That split is the whole point of keeping a row per report: when GN
     * says she is open Adriatic on the 2nd and Interscan says the Aegean on the 5th, both
     * are what we were told and the disagreement is worth seeing. When GN says it twice,
     * only the later one is still their position.
     */
    @Transactional
    public VesselPositionResponse create(VesselPositionRequest req) {
        VesselPosition p = new VesselPosition();
        apply(p, req);
        if (p.getStatus() == PositionStatus.LIVE) {
            supersedePrevious(p);
        }
        VesselPosition saved = positionRepository.save(p);
        return mapper.toVesselPositionResponse(saved, exNamesOf(saved.getVessel().getId()));
    }

    /**
     * Older live rows about this vessel from the same source, marked as replaced.
     *
     * <p>"Same source" includes having no source at all: two hand-typed positions with
     * nobody named are one person correcting themselves, not two brokers disagreeing.
     */
    private void supersedePrevious(VesselPosition incoming) {
        Long reporterId = incoming.getReportedByCompany() == null ? null
                : incoming.getReportedByCompany().getId();
        positionRepository.findByVesselIdOrderByReportedAtDesc(incoming.getVessel().getId()).stream()
                .filter(existing -> existing.getStatus() == PositionStatus.LIVE)
                .filter(existing -> {
                    Long existingReporter = existing.getReportedByCompany() == null ? null
                            : existing.getReportedByCompany().getId();
                    return java.util.Objects.equals(existingReporter, reporterId);
                })
                .forEach(existing -> existing.setStatus(PositionStatus.SUPERSEDED));
    }

    @Transactional
    public VesselPositionResponse update(Long id, VesselPositionRequest req) {
        VesselPosition p = load(id);
        apply(p, req);
        VesselPosition saved = positionRepository.save(p);
        return mapper.toVesselPositionResponse(saved, exNamesOf(saved.getVessel().getId()));
    }

    /**
     * Move a position out of the live set — she fixed, or the report was pulled.
     *
     * <p>Its own endpoint, like a cargo's status: it is one field, it is the most frequent
     * write this screen carries, and routing it through the whole-record form would let a
     * stale form revert the dates while marking her fixed.
     */
    @Transactional
    public VesselPositionResponse setStatus(Long id, PositionStatus status) {
        VesselPosition p = load(id);
        p.setStatus(status);
        VesselPosition saved = positionRepository.save(p);
        return mapper.toVesselPositionResponse(saved, exNamesOf(saved.getVessel().getId()));
    }

    @Transactional
    public void delete(Long id) {
        if (!positionRepository.existsById(id)) {
            throw new ResourceNotFoundException("Position", id);
        }
        positionRepository.deleteById(id);
    }

    private VesselPosition load(Long id) {
        return positionRepository.findWithDetailById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Position", id));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A mapper bound to one batch's former names.
     *
     * <p>Same reasoning as the vessel search: the ex-names live in another table and are on
     * every row, so they are read for the whole page in one query rather than per row.
     */
    private java.util.function.Function<VesselPosition, VesselPositionResponse> withExNames(
            List<VesselPosition> rows) {
        Map<Long, List<VesselExNameResponse>> byVessel = exNamesFor(rows);
        return p -> mapper.toVesselPositionResponse(
                p, byVessel.getOrDefault(p.getVessel().getId(), List.of()));
    }

    private Map<Long, List<VesselExNameResponse>> exNamesFor(List<VesselPosition> rows) {
        if (rows.isEmpty()) return Map.of();
        List<Long> ids = rows.stream().map(p -> p.getVessel().getId()).distinct().toList();
        return exNameRepository.findByVesselIds(ids).stream()
                .collect(Collectors.groupingBy(e -> e.getVessel().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(mapper::toVesselExNameResponse, Collectors.toList())));
    }

    private List<VesselExNameResponse> exNamesOf(Long vesselId) {
        return exNameRepository.findByVesselIdOrderByNameAsc(vesselId).stream()
                .map(mapper::toVesselExNameResponse).toList();
    }

    private void apply(VesselPosition p, VesselPositionRequest r) {
        Vessel v = vesselRepository.findById(r.getVesselId())
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", r.getVesselId()));
        p.setVessel(v);

        if (r.getStatus() != null && !r.getStatus().isBlank()) {
            p.setStatus(parseStatus(r.getStatus()));
        }

        p.setOpenPort(r.getOpenPortId() == null ? null : portRepository.findById(r.getOpenPortId())
                .orElseThrow(() -> new ResourceNotFoundException("Port", r.getOpenPortId())));
        p.setOpenPortText(blankToNull(r.getOpenPortText()));
        p.setOpenArea(r.getOpenAreaId() == null ? null
                : tradeAreaRepository.findById(r.getOpenAreaId())
                        .orElseThrow(() -> new ResourceNotFoundException("Trade area", r.getOpenAreaId())));

        p.setOpenFrom(r.getOpenFrom());
        p.setOpenTo(r.getOpenTo());
        p.setOpenText(blankToNull(r.getOpenText()));

        p.setLastCargo(blankToNull(r.getLastCargo()));
        p.setCargoPreferences(blankToNull(r.getCargoPreferences()));

        p.setReportedByCompany(r.getReportedByCompanyId() == null ? null
                : companyRepository.findById(r.getReportedByCompanyId())
                        .orElseThrow(() -> new ResourceNotFoundException("Company", r.getReportedByCompanyId())));
        p.setReportedByPerson(r.getReportedByPersonId() == null ? null
                : personRepository.findById(r.getReportedByPersonId())
                        .orElseThrow(() -> new ResourceNotFoundException("Person", r.getReportedByPersonId())));

        // Same rule as a cargo: the boolean follows the link rather than being sent beside
        // it, and once true it stays true - how this reading reached the desk is a fact
        // about the reading, not about the message row it came from.
        if (r.getSourceMailMessageId() != null) {
            p.setSourceMailMessage(mailMessageRepository.findById(r.getSourceMailMessageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Message", r.getSourceMailMessageId())));
            p.setFromMail(true);
        }
        if (r.getReportedAt() != null) p.setReportedAt(r.getReportedAt());
        else if (p.getReportedAt() == null) p.setReportedAt(OffsetDateTime.now());

        p.setNotes(blankToNull(r.getNotes()));
    }

    public static PositionStatus parseStatus(String value) {
        try {
            return PositionStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "status must be one of: " + Arrays.toString(PositionStatus.values()));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
