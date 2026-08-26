package com.chartering.service;

import com.chartering.model.TradeArea;
import com.chartering.model.TradeAreaAlias;
import com.chartering.model.TradeAreaDistance;
import com.chartering.repository.TradeAreaAliasRepository;
import com.chartering.repository.TradeAreaDistanceRepository;
import com.chartering.repository.TradeAreaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The trade-area vocabulary, held in memory and asked the two questions matching needs:
 * what does this phrase mean, and how far is it from there to here.
 *
 * <p><b>Why a cache rather than queries.</b> Twenty-seven areas, a hundred and twenty
 * aliases and a hundred and thirty distances — a few kilobytes that change when somebody
 * adds an alias, which is to say almost never. Match scores every live position against a
 * cargo; doing that with a join per pair would be hundreds of round trips to answer a
 * question the whole table fits inside.
 *
 * <p><b>What is cached is {@link Area}, not the entity.</b> A cached entity is a detached
 * entity, and the first caller to read {@code getParent()} on one outside the transaction
 * that loaded it gets a LazyInitializationException — from a field this class exists to
 * answer questions about. Flattening to a record at load time makes that impossible rather
 * than merely unlikely.
 *
 * <p>The snapshot is rebuilt on {@link #refresh()} and on first use, and is swapped in whole
 * rather than mutated, so a reader sees either the old vocabulary or the new one and never
 * half of each.
 */
@Service
@RequiredArgsConstructor
public class TradeAreaGraph {

    private final TradeAreaRepository areaRepository;
    private final TradeAreaAliasRepository aliasRepository;
    private final TradeAreaDistanceRepository distanceRepository;

    private volatile Snapshot snapshot;

    /** One area, flattened: no associations, nothing lazy, safe to hand anywhere. */
    public record Area(Long id, String code, String name, Long parentId, String parentCode,
                       int sortOrder, String notes) {
    }

    private record Snapshot(Map<Long, Area> areas,
                            List<Area> ordered,
                            Map<String, Long> byKey,
                            Map<Long, List<String>> aliasesByArea,
                            Map<String, Double> days) {
    }

    @Transactional(readOnly = true)
    public void refresh() {
        List<TradeArea> loaded = areaRepository.findAllByOrderBySortOrderAscNameAsc();

        // Parent ids first, off the proxies: getId() on an uninitialised proxy is answered
        // from the foreign key without a query, so this costs nothing and the codes are then
        // resolved from the map rather than by touching the association again.
        Map<Long, Long> parentIds = new HashMap<>();
        Map<Long, String> codes = new HashMap<>();
        for (TradeArea a : loaded) {
            codes.put(a.getId(), a.getCode());
            if (a.getParent() != null) parentIds.put(a.getId(), a.getParent().getId());
        }

        Map<Long, Area> areas = new LinkedHashMap<>();
        List<Area> ordered = new ArrayList<>(loaded.size());
        for (TradeArea a : loaded) {
            Long parentId = parentIds.get(a.getId());
            Area area = new Area(a.getId(), a.getCode(), a.getName(), parentId,
                    parentId == null ? null : codes.get(parentId), a.getSortOrder(), a.getNotes());
            areas.put(area.id(), area);
            ordered.add(area);
        }

        Map<String, Long> byKey = new HashMap<>();
        Map<Long, List<String>> aliasesByArea = new HashMap<>();
        for (TradeAreaAlias alias : aliasRepository.findAllWithArea()) {
            Long areaId = alias.getTradeArea().getId();
            String key = TradeAreaAlias.key(alias.getAlias());
            if (key != null) byKey.put(key, areaId);
            aliasesByArea.computeIfAbsent(areaId, k -> new ArrayList<>()).add(alias.getAlias());
        }
        aliasesByArea.values().forEach(list -> list.sort(Comparator.naturalOrder()));

        Map<String, Double> days = new HashMap<>();
        for (TradeAreaDistance d : distanceRepository.findAll()) {
            days.put(pair(d.getFromAreaId(), d.getToAreaId()), d.getBallastDays().doubleValue());
        }

        snapshot = new Snapshot(areas, List.copyOf(ordered), byKey, aliasesByArea, days);
    }

    private Snapshot snap() {
        Snapshot s = snapshot;
        if (s == null) {
            refresh();
            s = snapshot;
        }
        return s;
    }

    private static String pair(Long from, Long to) {
        return from + ":" + to;
    }

    // ---------------------------------------------------------------- reading

    public List<Area> all() {
        return snap().ordered();
    }

    public Area byId(Long id) {
        return id == null ? null : snap().areas().get(id);
    }

    public List<String> aliasesOf(Long areaId) {
        return snap().aliasesByArea().getOrDefault(areaId, List.of());
    }

    /**
     * What one written area means: "W.MED", "west med" and "SPAIN MED" all give West Med.
     * Exact after normalisation — punctuation and case are stripped, nothing else is
     * guessed.
     */
    public Optional<Area> resolve(String text) {
        String key = TradeAreaAlias.key(text);
        if (key == null) return Optional.empty();
        Snapshot s = snap();
        return Optional.ofNullable(s.byKey().get(key)).map(s.areas()::get);
    }

    /**
     * The area named somewhere inside a longer phrase — "SPOT AT MARMARA", "OPEN AT W.ITALY
     * 1/3 SEPT".
     *
     * <p>Longest alias wins, and that is not a detail: "MED" is an alias and so is "E.MED",
     * and any phrase containing the second contains the first. Shortest-first would resolve
     * every East Med position to the whole Mediterranean.
     *
     * <p>Written for the email parser that lands later; the forms on screen use
     * {@link #resolve(String)} against a dropdown, where the value is already exact.
     */
    public Optional<Area> findIn(String phrase) {
        if (phrase == null || phrase.isBlank()) return Optional.empty();
        String hay = TradeAreaAlias.key(phrase);
        if (hay == null) return Optional.empty();
        Snapshot s = snap();
        String best = null;
        for (String key : s.byKey().keySet()) {
            if (hay.contains(key) && (best == null || key.length() > best.length())) best = key;
        }
        return best == null ? Optional.empty() : Optional.ofNullable(s.areas().get(s.byKey().get(best)));
    }

    /** Is {@code candidate} the area itself, or somewhere inside it? */
    public boolean contains(Long area, Long candidate) {
        if (area == null || candidate == null) return false;
        Snapshot s = snap();
        Long cursor = candidate;
        // Bounded by the number of areas rather than trusting the data to be acyclic: a
        // parent chain that looped would otherwise hang a request.
        for (int hops = 0; cursor != null && hops <= s.areas().size(); hops++) {
            if (cursor.equals(area)) return true;
            Area a = s.areas().get(cursor);
            cursor = a == null ? null : a.parentId();
        }
        return false;
    }

    /**
     * Ballast days from one area to another, empty when nothing on file connects them.
     *
     * <p>Empty is a real answer, not a failure: the distance table is seeded only with pairs
     * this desk would consider, so "no figure" means far enough that Match should say so
     * rather than invent a number. The Caspian has no distances at all, which is correct — a
     * vessel there cannot ballast to a Med cargo in any number of days.
     *
     * <p>Zero when one area contains the other. A ship open in the West Med is already in
     * the Med, and a Med cargo does not care where in it she is.
     */
    public OptionalDouble ballastDays(Long from, Long to) {
        if (from == null || to == null) return OptionalDouble.empty();
        if (from.equals(to) || contains(from, to) || contains(to, from)) return OptionalDouble.of(0);

        Snapshot s = snap();
        Double direct = s.days().get(pair(from, to));
        if (direct != null) return OptionalDouble.of(direct);

        // No direct figure: try the pair one level out. A West Med position against a cargo
        // filed under the Baltic has no row, but WMED-CONT does, and CONT is where the
        // Baltic's parent chain gets to. Coarser than a direct figure, and clearly marked as
        // such by being the fallback - but better than declaring the two unreachable.
        Area fromArea = s.areas().get(from);
        Area toArea = s.areas().get(to);
        Long fromParent = fromArea == null ? null : fromArea.parentId();
        Long toParent = toArea == null ? null : toArea.parentId();

        Double viaFrom = fromParent == null ? null : s.days().get(pair(fromParent, to));
        if (viaFrom != null) return OptionalDouble.of(viaFrom);
        Double viaTo = toParent == null ? null : s.days().get(pair(from, toParent));
        if (viaTo != null) return OptionalDouble.of(viaTo);
        Double viaBoth = (fromParent == null || toParent == null) ? null
                : s.days().get(pair(fromParent, toParent));
        return viaBoth == null ? OptionalDouble.empty() : OptionalDouble.of(viaBoth);
    }
}
