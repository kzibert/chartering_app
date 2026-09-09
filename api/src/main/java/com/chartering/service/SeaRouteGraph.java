package com.chartering.service;

import com.chartering.model.Port;
import com.chartering.model.SeaLeg;
import com.chartering.model.SeaWaypoint;
import com.chartering.repository.SeaLegRepository;
import com.chartering.repository.SeaWaypointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How far it is from one berth to another by sea, and what the ship passes on the way.
 *
 * <p><b>Why this exists beside {@link TradeAreaGraph}.</b> That one answers "how many days
 * from the Black Sea to the West Med" — a broker's round figure between two waters, and the
 * right answer when all an email gave was a water. It cannot tell Constanza from Rostov,
 * and those are 250 miles and two days apart on the same trade area. Where both ends of a
 * pairing name a berth this database has placed, that is a better question and this answers
 * it; where either end does not, the area table is still the answer and nothing here is
 * consulted.
 *
 * <p><b>The model is a network, not a matrix.</b> Waypoints are points at sea; a leg between
 * two of them asserts that the water is open, so its distance is the great circle between
 * their coordinates unless the row overrides it. Ports hang off the network by a single
 * gateway. Sixty-odd nodes and a hundred legs describe every route this desk quotes, a new
 * port costs one link, and the answer for a pair nobody thought about is computed rather
 * than guessed — where a port-to-port table would be forty thousand numbers nobody would
 * keep true.
 *
 * <p><b>Shortest by distance, not by time.</b> The search minimises miles and then reports
 * the delays the chosen path happens to carry, rather than minimising the two together. It
 * would be defensible to weigh a convoy wait against a longer route, and it would need a
 * speed — which is a setting, which would make the cached network depend on a value the user
 * can change while a page is open. On the routes this network holds it changes nothing:
 * there is one way out of the Black Sea and one way through Suez, and neither has a rival to
 * be traded off against.
 *
 * <p><b>Nothing is found, sometimes, and that is an answer.</b> The Caspian waypoint has no
 * legs at all, so no route out of it exists — the same honest "too far to consider" the
 * sparse area table gives. Callers treat an empty result as "ask the area table", so a port
 * whose gateway nobody has set is no worse off than it was before this class existed.
 */
@Service
@RequiredArgsConstructor
public class SeaRouteGraph {

    private final SeaWaypointRepository waypointRepository;
    private final SeaLegRepository legRepository;

    /** Mean earth radius in nautical miles. */
    private static final double EARTH_RADIUS_NM = 3440.065;

    private volatile Snapshot snapshot;

    /**
     * Shortest paths from one waypoint to every other, computed once and kept.
     *
     * <p>Match scores every live position against every live cargo, which is a hundred
     * thousand pairings on a busy desk, and running a search per pairing would be the one
     * expensive thing in a feature that is otherwise arithmetic. There are fewer than a
     * hundred waypoints, so one search per <em>source</em> answers every pairing that starts
     * there. Cleared with the snapshot, and keyed on the snapshot's own identity so a
     * refresh cannot leave stale paths behind.
     */
    private final ConcurrentHashMap<Long, Paths> paths = new ConcurrentHashMap<>();

    /** One waypoint, flattened — no associations, nothing lazy, safe to hand anywhere. */
    public record Node(Long id, String code, String name, double latitude, double longitude,
                       double delayHours) {
    }

    /**
     * A passage, as the screen shows it.
     *
     * @param via the narrows passed through, in order — the Bosphorus, the Dardanelles,
     *            Suez. Only the waypoints that cost time are named: a list of a dozen
     *            open-water points is noise, and "via the Bosphorus and the Dardanelles" is
     *            what a broker would have said.
     */
    public record Route(double distanceNm, double delayHours, List<String> via) {
    }

    private record Snapshot(Map<Long, Node> nodes,
                            Map<String, Node> byCode,
                            Map<Long, Map<Long, Double>> adjacency) {
    }

    private record Paths(Map<Long, Double> distance, Map<Long, Long> previous) {
    }

    // --------------------------------------------------------------- loading

    @Transactional(readOnly = true)
    public void refresh() {
        Map<Long, Node> nodes = new LinkedHashMap<>();
        Map<String, Node> byCode = new HashMap<>();
        for (SeaWaypoint w : waypointRepository.findAll()) {
            Node n = new Node(w.getId(), w.getCode(), w.getName(),
                    w.getLatitude().doubleValue(), w.getLongitude().doubleValue(),
                    w.getDelayHours() == null ? 0 : w.getDelayHours().doubleValue());
            nodes.put(n.id(), n);
            byCode.put(n.code(), n);
        }

        Map<Long, Map<Long, Double>> adjacency = new HashMap<>();
        for (SeaLeg leg : legRepository.findAll()) {
            Node from = nodes.get(leg.getFromWaypointId());
            Node to = nodes.get(leg.getToWaypointId());
            if (from == null || to == null) continue;
            // A leg with no figure is a leg whose water is open, which is what putting the
            // row there asserts - so the great circle between the two points is the distance.
            BigDecimal override = leg.getDistanceNm();
            double nm = override != null ? override.doubleValue()
                    : greatCircleNm(from.latitude(), from.longitude(),
                                    to.latitude(), to.longitude());
            adjacency.computeIfAbsent(from.id(), k -> new HashMap<>())
                    .merge(to.id(), nm, Math::min);
        }

        // Swapped in whole rather than mutated, so a reader sees either the old network or
        // the new one and never half of each - and the path cache is dropped with it, or a
        // corrected leg would leave every route computed before it in place.
        snapshot = new Snapshot(nodes, byCode, adjacency);
        paths.clear();
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

    public List<Node> all() {
        return List.copyOf(snap().nodes().values());
    }

    public Optional<Node> byCode(String code) {
        return Optional.ofNullable(snap().byCode().get(code));
    }

    /**
     * The passage between two berths, or empty when the network cannot connect them.
     *
     * <p>Empty covers three different things and the caller treats them alike, because the
     * fallback is the same in all three: a port with no gateway, a port with no coordinates
     * where the gateway leg has to be computed, and a pair the network genuinely does not
     * join. All of them mean "ask the trade areas instead", which is the answer this feature
     * had before the network existed and is never worse than it.
     */
    public Optional<Route> between(Port from, Port to) {
        if (from == null || to == null) return Optional.empty();
        Double fromLeg = landfallNm(from);
        Double toLeg = landfallNm(to);
        if (fromLeg == null || toLeg == null) return Optional.empty();

        Long fromGate = gatewayId(from);
        Long toGate = gatewayId(to);
        if (fromGate == null || toGate == null) return Optional.empty();

        Snapshot s = snap();
        if (!s.nodes().containsKey(fromGate) || !s.nodes().containsKey(toGate)) {
            return Optional.empty();
        }

        // Two berths off one gateway - Izmit and Derince, Odessa and Chornomorsk. Their own
        // approach legs are the whole distance, and the sum is right because the ship comes
        // out to the gateway and goes back in.
        if (fromGate.equals(toGate)) {
            return Optional.of(new Route(round(fromLeg + toLeg), 0, List.of()));
        }

        Paths p = pathsFrom(fromGate);
        Double network = p.distance().get(toGate);
        if (network == null) return Optional.empty();

        List<Node> hops = reconstruct(p, fromGate, toGate);
        double delay = 0;
        List<String> via = new ArrayList<>();
        // The ends are excluded: a ship opening at Istanbul has not queued for the Bosphorus,
        // she is already there. A waypoint costs time when it is passed through.
        for (int i = 1; i < hops.size() - 1; i++) {
            Node hop = hops.get(i);
            if (hop.delayHours() > 0) {
                delay += hop.delayHours();
                via.add(hop.name());
            }
        }

        return Optional.of(new Route(round(fromLeg + network + toLeg), delay, List.copyOf(via)));
    }

    /**
     * How far the berth is from its own gateway.
     *
     * <p>The stored figure where there is one, and it is there for the river ports and
     * nothing else — Rostov up the Don, Izmail up the Danube. Otherwise the great circle from
     * the berth's coordinates, which is what a coastal port a few miles off the lane
     * actually sails. Null when neither is available, which is what makes a port nobody has
     * placed fall back rather than answer wrongly.
     */
    private Double landfallNm(Port port) {
        if (port.getGatewayNm() != null) return port.getGatewayNm().doubleValue();
        Long gate = gatewayId(port);
        if (gate == null || port.getLatitude() == null || port.getLongitude() == null) return null;
        Node node = snap().nodes().get(gate);
        if (node == null) return null;
        return greatCircleNm(port.getLatitude().doubleValue(), port.getLongitude().doubleValue(),
                node.latitude(), node.longitude());
    }

    /**
     * The gateway's id without loading it.
     *
     * <p>{@code getId()} on an uninitialised proxy is answered from the foreign key the port
     * row already carries, so this costs no query — which matters when Match asks it for
     * every one of a thousand positions.
     */
    private static Long gatewayId(Port port) {
        SeaWaypoint gate = port.getGatewayWaypoint();
        return gate == null ? null : gate.getId();
    }

    private Paths pathsFrom(Long source) {
        return paths.computeIfAbsent(source, this::dijkstra);
    }

    private Paths dijkstra(Long source) {
        Snapshot s = snap();
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        PriorityQueue<Long> queue = new PriorityQueue<>(
                java.util.Comparator.comparingDouble(id -> dist.getOrDefault(id, Double.MAX_VALUE)));

        dist.put(source, 0.0);
        queue.add(source);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            double here = dist.getOrDefault(current, Double.MAX_VALUE);
            for (Map.Entry<Long, Double> edge : s.adjacency()
                    .getOrDefault(current, Map.of()).entrySet()) {
                double candidate = here + edge.getValue();
                if (candidate < dist.getOrDefault(edge.getKey(), Double.MAX_VALUE)) {
                    dist.put(edge.getKey(), candidate);
                    prev.put(edge.getKey(), current);
                    // Re-queued rather than decrease-key'd: a duplicate entry is popped with a
                    // distance no better than the one already settled and does nothing, and
                    // the network is a hundred nodes.
                    queue.add(edge.getKey());
                }
            }
        }
        return new Paths(Map.copyOf(dist), Map.copyOf(prev));
    }

    private List<Node> reconstruct(Paths p, Long from, Long to) {
        Snapshot s = snap();
        List<Node> hops = new ArrayList<>();
        Long cursor = to;
        // Bounded by the node count rather than trusting the predecessor map to be acyclic:
        // a loop would otherwise hang a request, and this is on the path of every match.
        for (int i = 0; cursor != null && i <= s.nodes().size(); i++) {
            Node node = s.nodes().get(cursor);
            if (node != null) hops.add(node);
            if (cursor.equals(from)) break;
            cursor = p.previous().get(cursor);
        }
        Collections.reverse(hops);
        return hops;
    }

    // ----------------------------------------------------------------- maths

    /**
     * The great circle between two points, in nautical miles.
     *
     * <p>Haversine rather than the law of cosines: the difference only shows on very short
     * legs, and very short legs are exactly what a berth-to-gateway distance is.
     */
    public static double greatCircleNm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_NM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double round(double nm) {
        return Math.round(nm * 10) / 10.0;
    }
}
