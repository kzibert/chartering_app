package com.chartering.service;

import com.chartering.dto.*;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.*;
import com.chartering.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Which of the ships on file suit which of the cargoes in hand.
 *
 * <p><b>Computed on every request, stored never.</b> The live sets are small — a few hundred
 * positions against a handful of live cargoes — and scoring one pair is arithmetic over
 * fields already in memory. A stored score would go stale the moment a position or a cargo
 * moved, so it would need invalidating on every write in this feature, which is a great deal
 * of machinery to avoid a calculation that takes microseconds.
 *
 * <p>What <em>is</em> stored is the human's answer: {@link CargoVesselMatch} carries "not
 * this ship for this cargo, stop showing me", and without it this screen would propose the
 * same fifteen ships every morning, including the four already offered.
 */
@Service
@RequiredArgsConstructor
public class MatchService {

    private final CargoRepository cargoRepository;
    private final VesselPositionRepository positionRepository;
    private final CargoVesselMatchRepository matchRepository;
    private final VesselExNameRepository exNameRepository;
    private final VesselRepository vesselRepository;
    private final TradeAreaGraph tradeAreas;
    private final DtoMapper mapper;

    /**
     * The Match tab's landing view: every live cargo with the tonnage against it counted.
     *
     * <p>One pass over the live positions per cargo. With a hundred cargoes and a thousand
     * positions that is a hundred thousand comparisons of a dozen fields each, which is
     * nothing — and it is read once when the tab opens rather than per keystroke.
     */
    @Transactional(readOnly = true)
    public List<MatchSummaryResponse> overview() {
        List<Cargo> cargoes = cargoRepository.findForMatching(CargoService.LIVE_STATUSES);
        if (cargoes.isEmpty()) return List.of();

        List<VesselPosition> positions = livePositions();
        LocalDate today = LocalDate.now();

        List<MatchSummaryResponse> out = new ArrayList<>(cargoes.size());
        for (Cargo cargo : cargoes) {
            Map<Long, CargoVesselMatch> decided = decisionsFor(cargo.getId());
            int suitable = 0;
            int untouched = 0;
            int ruledOut = 0;
            int best = 0;
            for (VesselPosition p : positions) {
                MatchScorer.Result r = MatchScorer.score(cargo, p, tradeAreas, today);
                if (r.ruledOut()) {
                    ruledOut++;
                    continue;
                }
                suitable++;
                best = Math.max(best, r.score());
                CargoVesselMatch decision = decided.get(p.getVessel().getId());
                if (decision == null) untouched++;
            }
            out.add(new MatchSummaryResponse(
                    mapper.toCargoResponse(cargo), suitable, untouched, ruledOut, best));
        }
        // Most work first: a cargo with a dozen unworked ships against it is where the day
        // starts, and one with none needs a different kind of attention than a good score.
        out.sort(Comparator.comparingInt(MatchSummaryResponse::untouched).reversed()
                .thenComparing(Comparator.comparingInt(MatchSummaryResponse::bestScore).reversed()));
        return out;
    }

    /**
     * Tonnage for one cargo, best first.
     *
     * @param includeRuledOut also return the pairs that failed a check, with the reason. Worth
     *                        having on a switch rather than never: "why is LADY LAURA not on
     *                        this list" is a question with an answer, and hiding it makes the
     *                        screen look arbitrary.
     */
    @Transactional(readOnly = true)
    public List<MatchResponse> forCargo(Long cargoId, boolean includeRuledOut, Integer minScore) {
        Cargo cargo = cargoRepository.findWithDetailById(cargoId)
                .orElseThrow(() -> new ResourceNotFoundException("Cargo", cargoId));
        List<VesselPosition> positions = livePositions();
        Map<Long, CargoVesselMatch> decided = decisionsFor(cargoId);
        Map<Long, List<VesselExNameResponse>> exNames = exNamesFor(positions);
        CargoResponse cargoDto = mapper.toCargoResponse(cargo);
        LocalDate today = LocalDate.now();

        return positions.stream()
                .map(p -> {
                    MatchScorer.Result r = MatchScorer.score(cargo, p, tradeAreas, today);
                    return toResponse(cargoDto, p, r, decided.get(p.getVessel().getId()), exNames);
                })
                .filter(m -> includeRuledOut || !m.ruledOut())
                .filter(m -> minScore == null || m.ruledOut() || m.score() >= minScore)
                .sorted(bestFirst())
                .toList();
    }

    /**
     * The other direction: cargoes for one ship's position.
     *
     * <p>Not a nicety. Most of the mail on this desk is somebody else's tonnage asking for
     * work — "PLS PROPOSE SUITABLE CGOES FOR OUR BELOW HOME TONNAGES" arrives weekly — and
     * answering it is exactly this query. Same scorer, same checks, read the other way round.
     */
    @Transactional(readOnly = true)
    public List<MatchResponse> forPosition(Long positionId, boolean includeRuledOut, Integer minScore) {
        VesselPosition position = positionRepository.findWithDetailById(positionId)
                .orElseThrow(() -> new ResourceNotFoundException("Position", positionId));
        List<Cargo> cargoes = cargoRepository.findForMatching(CargoService.LIVE_STATUSES);
        Map<Long, List<VesselExNameResponse>> exNames = exNamesFor(List.of(position));
        Map<Long, CargoVesselMatch> byCargo = matchRepository
                .findByVesselId(position.getVessel().getId()).stream()
                .collect(Collectors.toMap(m -> m.getCargo().getId(), m -> m, (a, b) -> a));
        LocalDate today = LocalDate.now();

        return cargoes.stream()
                .map(cargo -> {
                    MatchScorer.Result r = MatchScorer.score(cargo, position, tradeAreas, today);
                    return toResponse(mapper.toCargoResponse(cargo), position, r,
                            byCargo.get(cargo.getId()), exNames);
                })
                .filter(m -> includeRuledOut || !m.ruledOut())
                .filter(m -> minScore == null || m.ruledOut() || m.score() >= minScore)
                .sorted(bestFirst())
                .toList();
    }

    /**
     * Record what was done about a pairing.
     *
     * <p>One row per pair, updated rather than appended: offering a ship twice is a correction
     * to the first answer, not a second one.
     */
    @Transactional
    public MatchResponse decide(Long cargoId, Long vesselId, MatchOutcomeRequest req, String by) {
        Cargo cargo = cargoRepository.findWithDetailById(cargoId)
                .orElseThrow(() -> new ResourceNotFoundException("Cargo", cargoId));
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new ResourceNotFoundException("Vessel", vesselId));

        CargoVesselMatch m = matchRepository.findByCargoIdAndVesselId(cargoId, vesselId)
                .orElseGet(CargoVesselMatch::new);
        m.setCargo(cargo);
        m.setVessel(vessel);
        m.setOutcome(parseOutcome(req.getOutcome()));
        m.setNote(req.getNote() == null || req.getNote().isBlank() ? null : req.getNote());
        m.setDecidedBy(by);
        if (req.getVesselPositionId() != null) {
            m.setVesselPosition(positionRepository.findById(req.getVesselPositionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Position", req.getVesselPositionId())));
        }
        CargoVesselMatch saved = matchRepository.save(m);

        // Answered with the pairing re-scored, so the row the caller replaces on screen is the
        // whole truth rather than the old score wearing a new label.
        VesselPosition position = saved.getVesselPosition() != null
                ? saved.getVesselPosition()
                : currentPositionOf(vesselId);
        if (position == null) {
            // She has no live position - the decision still stands, there is simply nothing
            // to score it against.
            return new MatchResponse(mapper.toCargoResponse(cargo), null, 0, false, 0, List.of(),
                    null, null, saved.getOutcome().name(), saved.getNote());
        }
        MatchScorer.Result r = MatchScorer.score(cargo, position, tradeAreas, LocalDate.now());
        return toResponse(mapper.toCargoResponse(cargo), position, r, saved,
                exNamesFor(List.of(position)));
    }

    @Transactional
    public void clearDecision(Long cargoId, Long vesselId) {
        matchRepository.findByCargoIdAndVesselId(cargoId, vesselId)
                .ifPresent(matchRepository::delete);
    }

    // --------------------------------------------------------------- helpers

    private List<VesselPosition> livePositions() {
        return positionRepository.findCurrentPositions(PositionStatus.LIVE).stream()
                // Russian-rooted hulls are out of every list in this app, and a matching
                // screen is the last place to make an exception: the whole purpose of the
                // flag is that they are not offered.
                .filter(p -> !p.getVessel().isBanned())
                .toList();
    }

    private Map<Long, CargoVesselMatch> decisionsFor(Long cargoId) {
        return matchRepository.findByCargoId(cargoId).stream()
                .collect(Collectors.toMap(m -> m.getVessel().getId(), m -> m, (a, b) -> a));
    }

    private Map<Long, List<VesselExNameResponse>> exNamesFor(List<VesselPosition> positions) {
        if (positions.isEmpty()) return Map.of();
        List<Long> ids = positions.stream().map(p -> p.getVessel().getId()).distinct().toList();
        return exNameRepository.findByVesselIds(ids).stream()
                .collect(Collectors.groupingBy(e -> e.getVessel().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(mapper::toVesselExNameResponse, Collectors.toList())));
    }

    private VesselPosition currentPositionOf(Long vesselId) {
        return positionRepository.findByVesselIdOrderByReportedAtDesc(vesselId).stream()
                .filter(p -> p.getStatus() == PositionStatus.LIVE)
                .findFirst()
                .orElse(null);
    }

    private MatchResponse toResponse(CargoResponse cargo, VesselPosition position,
                                     MatchScorer.Result r, CargoVesselMatch decision,
                                     Map<Long, List<VesselExNameResponse>> exNames) {
        return new MatchResponse(
                cargo,
                mapper.toVesselPositionResponse(position,
                        exNames.getOrDefault(position.getVessel().getId(), List.of())),
                r.score(), r.ruledOut(), r.unknownCount(),
                r.checks().stream()
                        .map(c -> new MatchCheckResponse(c.code(), c.label(),
                                c.verdict().name(), c.weight(), c.detail()))
                        .toList(),
                r.ballastDays(), r.earliestArrival(),
                decision == null ? null : decision.getOutcome().name(),
                decision == null ? null : decision.getNote());
    }

    /**
     * Best first, and "best" puts undecided pairings above closed ones at the same score.
     *
     * <p>A ship already declined for this cargo is not a suggestion, and it would otherwise
     * sit at the top of the list every morning being scrolled past.
     */
    private static Comparator<MatchResponse> bestFirst() {
        return Comparator
                .comparing((MatchResponse m) -> m.ruledOut() ? 1 : 0)
                .thenComparing(m -> isClosed(m.outcome()) ? 1 : 0)
                .thenComparing(Comparator.comparingInt(MatchResponse::score).reversed())
                .thenComparing(MatchResponse::unknowns);
    }

    private static boolean isClosed(String outcome) {
        if (outcome == null) return false;
        try {
            return MatchOutcome.valueOf(outcome).isClosed();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static MatchOutcome parseOutcome(String value) {
        try {
            return MatchOutcome.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "outcome must be one of: " + Arrays.toString(MatchOutcome.values()));
        }
    }
}
