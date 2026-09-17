package com.chartering.service;

import com.chartering.dto.CargoRequest;
import com.chartering.dto.CargoResponse;
import com.chartering.dto.PageResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Cargo;
import com.chartering.model.SourceKind;
import com.chartering.model.CargoSource;
import com.chartering.model.CargoStatus;
import com.chartering.repository.*;
import com.chartering.specification.CargoSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CargoService {

    private final CargoRepository cargoRepository;
    private final CargoSourceRepository cargoSourceRepository;
    private final SettingsService settingsService;
    private final PortRepository portRepository;
    private final TradeAreaRepository tradeAreaRepository;
    private final CompanyRepository companyRepository;
    private final PersonRepository personRepository;
    private final MailMessageRepository mailMessageRepository;
    private final DtoMapper mapper;

    /**
     * What the Cargoes tab and Match both mean by "still worth working".
     *
     * <p>Derived from the enum rather than listed here, so adding a status is one edit in
     * {@link CargoStatus} and not two in two files that would eventually disagree.
     */
    public static final List<CargoStatus> LIVE_STATUSES =
            Arrays.stream(CargoStatus.values()).filter(CargoStatus::isLive).toList();

    /** What a freshly read cargo is tested against as a possible repeat. See the enum. */
    public static final List<CargoStatus> RECOGNISED_ON_ARRIVAL_STATUSES =
            Arrays.stream(CargoStatus.values()).filter(CargoStatus::isRecognisedOnArrival).toList();

    public record CargoFilter(String commodity,
                              List<CargoStatus> status,
                              Long loadAreaId,
                              Long dischargeAreaId,
                              Long loadPortId,
                              String loadPlace,
                              String dischargePlace,
                              LocalDate laycanFrom,
                              LocalDate laycanTo,
                              BigDecimal minQuantity,
                              BigDecimal maxQuantity,
                              LocalDate sentSince,
                              Long companyId,
                              SourceKind sourceKind) {
    }

    /**
     * One page of cargoes, each with who sent it.
     *
     * <p>The senders come from one query for the whole page rather than one per row, and only
     * here: the list is the one screen that prints them on every row, and the drawer reads the
     * full arrivals list on its own.
     */
    @Transactional(readOnly = true)
    public PageResponse<CargoResponse> search(CargoFilter f, Pageable pageable) {
        Page<Cargo> page = cargoRepository.findAll(buildSpec(f), pageable);
        List<Long> ids = page.getContent().stream().map(Cargo::getId).toList();
        // Our own replies are arrivals the sweep read, not senders - see Cargo.lastSentAt.
        Set<String> own = ids.isEmpty() ? Set.of() : settingsService.ownAddresses();
        Map<Long, List<CargoSource>> sources = ids.isEmpty() ? Map.of()
                : cargoSourceRepository.findByCargoIdInOrderByReportedAtDesc(ids).stream()
                        .filter(s -> !SettingsService.isOwn(s.getFromAddress(), own))
                        .collect(Collectors.groupingBy(s -> s.getCargo().getId()));
        return PageResponse.from(page.map(c ->
                mapper.toCargoResponse(c, sources.getOrDefault(c.getId(), List.of()))));
    }

    private Specification<Cargo> buildSpec(CargoFilter f) {
        return Specification.allOf(
                CargoSpecification.commodityContains(f.commodity()),
                CargoSpecification.statusIn(f.status()),
                CargoSpecification.loadAreaEquals(f.loadAreaId()),
                CargoSpecification.dischargeAreaEquals(f.dischargeAreaId()),
                CargoSpecification.loadPortEquals(f.loadPortId()),
                CargoSpecification.loadPlaceContains(f.loadPlace()),
                CargoSpecification.dischargePlaceContains(f.dischargePlace()),
                CargoSpecification.laycanOverlaps(f.laycanFrom(), f.laycanTo()),
                CargoSpecification.quantityOverlaps(f.minQuantity(), f.maxQuantity()),
                CargoSpecification.lastSentSince(f.sentSince()),
                CargoSpecification.companyIdEquals(f.companyId()),
                CargoSpecification.sourceKindEquals(f.sourceKind()));
    }

    @Transactional(readOnly = true)
    public CargoResponse get(Long id) {
        return mapper.toCargoResponse(load(id));
    }

    @Transactional
    public CargoResponse create(CargoRequest req) {
        Cargo c = new Cargo();
        apply(c, req);
        return mapper.toCargoResponse(cargoRepository.save(c));
    }

    @Transactional
    public CargoResponse update(Long id, CargoRequest req) {
        Cargo c = load(id);
        apply(c, req);
        return mapper.toCargoResponse(cargoRepository.save(c));
    }

    /**
     * Move a cargo along without resending the rest of it.
     *
     * <p>Its own endpoint rather than part of the update, for the same reason confirm and ban
     * are: marking a cargo fixed from a list is one click on one fact, and routing it through
     * a form that replaces every field means a stale form quietly reverting five others.
     */
    @Transactional
    public CargoResponse setStatus(Long id, CargoStatus status, String note) {
        Cargo c = load(id);
        c.setStatus(status);
        if (note != null) c.setStatusNote(note.isBlank() ? null : note);
        return mapper.toCargoResponse(cargoRepository.save(c));
    }

    @Transactional
    public void delete(Long id) {
        if (!cargoRepository.existsById(id)) throw new ResourceNotFoundException("Cargo", id);
        cargoRepository.deleteById(id);
    }

    private Cargo load(Long id) {
        return cargoRepository.findWithDetailById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cargo", id));
    }

    private void apply(Cargo c, CargoRequest r) {
        c.setCommodity(r.getCommodity());
        if (r.getStatus() != null && !r.getStatus().isBlank()) {
            c.setStatus(parseStatus(r.getStatus()));
        }
        c.setStatusNote(blankToNull(r.getStatusNote()));
        c.setStowageFactor(r.getStowageFactor());

        c.setQuantity(r.getQuantity());
        if (r.getQuantityUnit() != null && !r.getQuantityUnit().isBlank()) {
            c.setQuantityUnit(r.getQuantityUnit());
        }
        c.setQuantityTolerance(blankToNull(r.getQuantityTolerance()));
        applyQuantityRange(c, r);

        c.setLoadPort(r.getLoadPortId() == null ? null : portRepository.findById(r.getLoadPortId())
                .orElseThrow(() -> new ResourceNotFoundException("Port", r.getLoadPortId())));
        c.setLoadPortText(blankToNull(r.getLoadPortText()));
        c.setLoadArea(area(r.getLoadAreaId()));

        c.setDischargePort(r.getDischargePortId() == null ? null
                : portRepository.findById(r.getDischargePortId())
                        .orElseThrow(() -> new ResourceNotFoundException("Port", r.getDischargePortId())));
        c.setDischargePortText(blankToNull(r.getDischargePortText()));
        c.setDischargeArea(area(r.getDischargeAreaId()));

        c.setLaycanFrom(r.getLaycanFrom());
        c.setLaycanTo(r.getLaycanTo());
        c.setLaycanText(blankToNull(r.getLaycanText()));

        c.setMaxDraft(r.getMaxDraft());
        c.setMinDwt(r.getMinDwt());
        c.setMaxDwt(r.getMaxDwt());
        c.setMaxAgeYears(r.getMaxAgeYears());
        c.setRequiresGeared(r.getRequiresGeared());
        c.setRequiresGrainFitted(r.getRequiresGrainFitted());
        c.setRequiresImoFitted(r.getRequiresImoFitted());
        c.setMaxBallastDays(r.getMaxBallastDays());

        c.setFreightIdea(blankToNull(r.getFreightIdea()));
        c.setCommission(blankToNull(r.getCommission()));
        c.setTerms(blankToNull(r.getTerms()));
        c.setLoadRate(blankToNull(r.getLoadRate()));
        c.setDischargeRate(blankToNull(r.getDischargeRate()));

        c.setChartererCompany(r.getChartererCompanyId() == null ? null
                : companyRepository.findById(r.getChartererCompanyId())
                        .orElseThrow(() -> new ResourceNotFoundException("Company", r.getChartererCompanyId())));
        c.setBrokerCompany(r.getBrokerCompanyId() == null ? null
                : companyRepository.findById(r.getBrokerCompanyId())
                        .orElseThrow(() -> new ResourceNotFoundException("Company", r.getBrokerCompanyId())));
        c.setBrokerPerson(r.getBrokerPersonId() == null ? null
                : personRepository.findById(r.getBrokerPersonId())
                        .orElseThrow(() -> new ResourceNotFoundException("Person", r.getBrokerPersonId())));

        // sourceKind follows the link rather than being sent alongside it: two fields saying
        // the same thing are two fields that eventually disagree. Once set it stays set even
        // if the message is later unlinked - how the cargo reached the desk is a fact about
        // the cargo, not about the row it was read from. A form that names no message leaves
        // whatever is there, so saving a cargo the sweep read does not demote it to MANUAL.
        if (r.getSourceMailMessageId() != null) {
            c.setSourceMailMessage(mailMessageRepository.findById(r.getSourceMailMessageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Message", r.getSourceMailMessageId())));
            c.setSourceKind(SourceKind.MAIL);
        }
        c.setReceivedAt(r.getReceivedAt());
        c.setNotes(blankToNull(r.getNotes()));
    }

    /**
     * Fill the range Match compares hulls against.
     *
     * <p>A range the caller sent always wins: the arithmetic is a convenience, and a broker
     * who has typed a range knows something about this cargo that "+/- 10%" did not say.
     * Otherwise it is derived, and left null when the tolerance is not a percentage — see
     * {@link QuantityTolerance} for why guessing one would be worse than leaving it empty.
     */
    private void applyQuantityRange(Cargo c, CargoRequest r) {
        if (r.getQuantityMin() != null || r.getQuantityMax() != null) {
            c.setQuantityMin(r.getQuantityMin());
            c.setQuantityMax(r.getQuantityMax());
            return;
        }
        Optional<QuantityTolerance.Range> range =
                QuantityTolerance.rangeOf(r.getQuantity(), r.getQuantityTolerance());
        c.setQuantityMin(range.map(QuantityTolerance.Range::min).orElse(null));
        c.setQuantityMax(range.map(QuantityTolerance.Range::max).orElse(null));
    }

    private com.chartering.model.TradeArea area(Long id) {
        return id == null ? null : tradeAreaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Trade area", id));
    }

    /** Names the valid values in the message; the enum's own error says only "no enum". */
    public static CargoStatus parseStatus(String value) {
        try {
            return CargoStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "status must be one of: " + Arrays.toString(CargoStatus.values()));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
