package com.chartering.service;

import com.chartering.model.Port;
import com.chartering.model.PortAlias;
import com.chartering.model.TradeArea;
import com.chartering.repository.PortAliasRepository;
import com.chartering.repository.PortRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What one written place name means: a berth, and the water it sits on.
 *
 * <p>Two questions get asked of this class and they are the same two {@link TradeAreaGraph}
 * answers, one level finer. It is the finer level that the parser needed and did not have:
 * {@code PortRepository.findByExactName} matches the name as the ports table happens to
 * spell it and nothing else, so a circular writing ILLICHIVSK for a port renamed in 2016, or
 * BOMBAY for one renamed in 1995, resolved to nothing at all and the position landed with
 * only an area on it.
 *
 * <p><b>A port's own name always beats an alias.</b> The two live in different tables and no
 * index can span them, so the rule lives here — and it is what makes an alias safe to add:
 * the worst a wrong one can do is answer a question no port name already answered. A name
 * two ports share answers nothing, the same refusal the vessel and company lookups make when
 * a match is not unique, and for the same reason: a machine choosing between two berths
 * would file a ship in the wrong sea and nothing downstream would ask.
 *
 * <p><b>Cached, flattened, and swapped whole</b> — the same discipline
 * {@link TradeAreaGraph} keeps and for the same reason. A few hundred ports and a hundred
 * aliases are a few kilobytes that change when somebody adds a berth; caching the entities
 * instead would hand a caller a detached {@link Port} whose {@code getTradeArea()} throws
 * outside the transaction that loaded it. Callers that need the row take the id and load it.
 */
@Service
@RequiredArgsConstructor
public class PortDirectory {

    private final PortRepository portRepository;
    private final PortAliasRepository aliasRepository;

    /**
     * Below this, a lone word in a longer phrase is not treated as a port name.
     *
     * <p>Only {@link #findIn} is affected, and only for a word standing on its own: "Bar" and
     * "Male" are real ports and also ordinary words, and scanning a broker's prose for them
     * would file a ship in Montenegro because somebody wrote "grain in bulk, bar none". An
     * exact {@link #resolve} of a field that contains just "Bar" is a different act — a human
     * or a model put that there as the port — and carries no such guard.
     */
    private static final int MIN_LONE_WORD = 4;

    /** How many consecutive words a scan will join looking for a name. "Bandar Imam Khomeini". */
    private static final int MAX_PHRASE_WORDS = 4;

    private volatile Snapshot snapshot;

    /** One berth, flattened: no associations, nothing lazy, safe to hand anywhere. */
    public record Berth(Long id, String name, String country,
                        Long tradeAreaId, String tradeAreaCode) {
    }

    private record Snapshot(Map<Long, Berth> berths,
                            Map<String, Long> byKey,
                            Map<Long, List<String>> aliasesByPort) {
    }

    @Transactional(readOnly = true)
    public void refresh() {
        Map<Long, Berth> berths = new LinkedHashMap<>();
        Map<String, Long> byName = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();

        for (Port p : portRepository.findAllWithArea()) {
            TradeArea area = p.getTradeArea();
            berths.put(p.getId(), new Berth(p.getId(), p.getName(), p.getCountry(),
                    area == null ? null : area.getId(),
                    area == null ? null : area.getCode()));
            String key = PortAlias.key(p.getName());
            if (key == null) continue;
            Long existing = byName.put(key, p.getId());
            // Two rows of one name: the ports table has no unique index on it and never has.
            // Neither answers, which is the same refusal findByExactName makes.
            if (existing != null && !existing.equals(p.getId())) ambiguous.add(key);
        }
        ambiguous.forEach(byName::remove);

        // Names first, then aliases layered under them: putIfAbsent is the rule "a port's own
        // name always beats an alias", written once.
        Map<String, Long> byKey = new HashMap<>(byName);
        Map<Long, List<String>> aliasesByPort = new HashMap<>();
        for (PortAlias alias : aliasRepository.findAllWithPort()) {
            Long portId = alias.getPort().getId();
            String key = PortAlias.key(alias.getAlias());
            if (key != null) byKey.putIfAbsent(key, portId);
            aliasesByPort.computeIfAbsent(portId, k -> new ArrayList<>()).add(alias.getAlias());
        }
        aliasesByPort.values().forEach(list -> list.sort(String.CASE_INSENSITIVE_ORDER));

        snapshot = new Snapshot(berths, byKey, aliasesByPort);
    }

    private Snapshot snap() {
        Snapshot s = snapshot;
        if (s == null) {
            refresh();
            s = snapshot;
        }
        return s;
    }

    // --------------------------------------------------------------- reading

    public Optional<Berth> byId(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(snap().berths().get(id));
    }

    public List<String> aliasesOf(Long portId) {
        return snap().aliasesByPort().getOrDefault(portId, List.of());
    }

    /**
     * A whole field read as a port name. "ODESSA", "Port Said", "illichivsk".
     *
     * <p>Exact after normalisation — case and punctuation are stripped and nothing else is
     * guessed. Ports carry names that differ from each other by a word ("Port Said" and "Port
     * Sudan", a dozen Alexandrias), and a near match here would move a ship to another sea
     * without anything on screen saying it had guessed.
     */
    public Optional<Berth> resolve(String text) {
        String key = PortAlias.key(text);
        if (key == null) return Optional.empty();
        Snapshot s = snap();
        return Optional.ofNullable(s.byKey().get(key)).map(s.berths()::get);
    }

    /**
     * A port named somewhere inside a longer phrase — "SALERNO 1/2 SEPT", "SPOT ODESSA PPT".
     *
     * <p>Words, not characters, and that is the difference from {@link TradeAreaGraph#findIn}.
     * Area names are distinctive enough to look for as substrings; port names are short and
     * many of them are ordinary words, so scanning for "BAR" inside a sentence finds a berth
     * in Montenegro in the middle of "grain in bulk, bar none". This joins consecutive words
     * instead and matches the result exactly, longest run first — which finds "Bandar Imam
     * Khomeini" without finding "Imam", and finds nothing at all rather than something
     * plausible.
     */
    public Optional<Berth> findIn(String phrase) {
        if (phrase == null || phrase.isBlank()) return Optional.empty();
        String[] words = phrase.split("[^A-Za-z0-9]+");
        Snapshot s = snap();

        for (int span = MAX_PHRASE_WORDS; span >= 1; span--) {
            for (int start = 0; start + span <= words.length; start++) {
                StringBuilder joined = new StringBuilder();
                for (int i = start; i < start + span; i++) joined.append(words[i]);
                String key = PortAlias.key(joined.toString());
                if (key == null) continue;
                if (span == 1 && key.length() < MIN_LONE_WORD) continue;
                Long id = s.byKey().get(key);
                if (id != null) return Optional.ofNullable(s.berths().get(id));
            }
        }
        return Optional.empty();
    }
}
