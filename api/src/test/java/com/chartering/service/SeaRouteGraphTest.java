package com.chartering.service;

import com.chartering.model.Port;
import com.chartering.model.SeaLeg;
import com.chartering.model.SeaWaypoint;
import com.chartering.repository.SeaLegRepository;
import com.chartering.repository.SeaWaypointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sea network, on a scale small enough to check by hand.
 *
 * <p>The tests worth reading are the two about absence. A pair the network does not join and
 * a port nobody has placed both come back empty, and the caller treats them the same way —
 * it asks the trade areas instead. That is what makes this safe to add to a working feature:
 * the worst it can do is decline to answer.
 */
class SeaRouteGraphTest {

    private SeaWaypointRepository waypoints;
    private SeaLegRepository legs;
    private SeaRouteGraph graph;

    // A miniature of the real thing: two waters joined by a strait that costs time, and an
    // island sea joined to nothing, which is what the Caspian is.
    private final SeaWaypoint north = waypoint(1L, "NORTH", 41.90, 29.10, 0);
    private final SeaWaypoint strait = waypoint(2L, "STRAIT", 41.00, 29.00, 12);
    private final SeaWaypoint south = waypoint(3L, "SOUTH", 40.10, 26.40, 0);
    private final SeaWaypoint closed = waypoint(4L, "CLOSED", 41.50, 51.00, 0);

    @BeforeEach
    void setUp() {
        waypoints = mock(SeaWaypointRepository.class);
        legs = mock(SeaLegRepository.class);
        graph = new SeaRouteGraph(waypoints, legs);
        when(waypoints.findAll()).thenReturn(List.of(north, strait, south, closed));
        when(legs.findAll()).thenReturn(both(leg(1L, 2L, null), leg(2L, 3L, new BigDecimal("100"))));
        graph.refresh();
    }

    @Test
    void addsUpTheLegsAndTheTwoApproaches() {
        // North point to the strait is computed from the coordinates; the strait to the south
        // point is overridden at 100. Each berth adds its own approach.
        Port from = port(north, 42.00, 29.10, null);
        Port to = port(south, 40.05, 26.40, null);

        SeaRouteGraph.Route route = graph.between(from, to).orElseThrow();

        double gate1 = SeaRouteGraph.greatCircleNm(42.00, 29.10, 41.90, 29.10);
        double gate2 = SeaRouteGraph.greatCircleNm(40.05, 26.40, 40.10, 26.40);
        double network = SeaRouteGraph.greatCircleNm(41.90, 29.10, 41.00, 29.00) + 100;
        assertThat(route.distanceNm()).isCloseTo(gate1 + network + gate2, within(0.2));
    }

    @Test
    void chargesTheStraitOnlyToShipsThatPassThroughIt() {
        Port from = port(north, 42.00, 29.10, null);
        Port to = port(south, 40.05, 26.40, null);
        assertThat(graph.between(from, to).orElseThrow().delayHours()).isEqualTo(12);
        assertThat(graph.between(from, to).orElseThrow().via()).containsExactly("STRAIT");

        // A ship already at the strait has not queued for it: it is where she is, not
        // something she passes.
        Port atStrait = port(strait, 41.00, 29.00, null);
        assertThat(graph.between(atStrait, to).orElseThrow().delayHours()).isZero();
        assertThat(graph.between(atStrait, to).orElseThrow().via()).isEmpty();
    }

    @Test
    void takesTheStoredApproachOverTheStraightLine() {
        // The river ports. Rostov's coordinates are 40 miles from the Kerch Strait as the
        // crow flies and 250 as the ship sails, and no coordinate pair could say so.
        Port river = port(north, 42.00, 29.10, new BigDecimal("250"));
        Port to = port(south, 40.05, 26.40, null);

        double straight = graph.between(port(north, 42.00, 29.10, null), to)
                .orElseThrow().distanceNm();
        double sailed = graph.between(river, to).orElseThrow().distanceNm();

        assertThat(sailed - straight)
                .isCloseTo(250 - SeaRouteGraph.greatCircleNm(42.00, 29.10, 41.90, 29.10),
                        within(0.2));
    }

    @Test
    void countsBothApproachesForTwoBerthsOffOneGateway() {
        // Izmit and Derince. She comes out to the gateway and goes back in, so the two
        // approach legs are the whole passage.
        Port a = port(north, 42.00, 29.10, new BigDecimal("30"));
        Port b = port(north, 41.95, 29.20, new BigDecimal("20"));

        assertThat(graph.between(a, b).orElseThrow().distanceNm()).isEqualTo(50);
    }

    @Test
    void answersNothingForAWaterJoinedToNothing() {
        // The Caspian, and the honest answer. A vessel there cannot ballast to a Med cargo in
        // any number of days, so no route is the right result rather than a large number.
        Port caspian = port(closed, 43.64, 51.20, null);
        Port to = port(south, 40.05, 26.40, null);

        assertThat(graph.between(caspian, to)).isEmpty();
    }

    @Test
    void answersNothingForABerthNobodyHasPlaced() {
        // Which is what sends the caller back to the trade-area table, so a port this
        // application has not given coordinates to is no worse off than it was before.
        Port unplaced = new Port();
        unplaced.setName("Somewhere");
        assertThat(graph.between(unplaced, port(south, 40.05, 26.40, null))).isEmpty();
        assertThat(graph.between(port(north, 42.00, 29.10, null), null)).isEmpty();
    }

    @Test
    void measuresAKnownDistanceToWithinAFewMiles() {
        // A degree of latitude is sixty nautical miles, which is the check that says the
        // arithmetic is in the right units at all.
        assertThat(SeaRouteGraph.greatCircleNm(40, 0, 41, 0)).isCloseTo(60, within(0.5));
        // London to New York, whose great circle everybody knows to be about 3,000 nautical
        // miles. Not a sailing distance - it is the check that the earth's radius and the
        // trigonometry agree over a long leg as well as a short one.
        assertThat(SeaRouteGraph.greatCircleNm(51.50, -0.13, 40.71, -74.01))
                .isCloseTo(3000, within(20.0));
    }

    // ------------------------------------------------------------- fixtures

    private static SeaWaypoint waypoint(Long id, String code, double lat, double lon, int delay) {
        SeaWaypoint w = new SeaWaypoint();
        w.setId(id);
        w.setCode(code);
        w.setName(code);
        w.setLatitude(BigDecimal.valueOf(lat));
        w.setLongitude(BigDecimal.valueOf(lon));
        w.setDelayHours(BigDecimal.valueOf(delay));
        return w;
    }

    private static SeaLeg leg(Long from, Long to, BigDecimal nm) {
        SeaLeg l = new SeaLeg();
        l.setFromWaypointId(from);
        l.setToWaypointId(to);
        l.setDistanceNm(nm);
        return l;
    }

    /** The seed writes both directions; so does this, or half the routes would not exist. */
    private static List<SeaLeg> both(SeaLeg... one) {
        List<SeaLeg> all = new ArrayList<>();
        for (SeaLeg l : one) {
            all.add(l);
            all.add(leg(l.getToWaypointId(), l.getFromWaypointId(), l.getDistanceNm()));
        }
        return all;
    }

    private static Port port(SeaWaypoint gateway, double lat, double lon, BigDecimal gatewayNm) {
        Port p = new Port();
        p.setName(gateway.getCode() + " berth");
        p.setGatewayWaypoint(gateway);
        p.setLatitude(BigDecimal.valueOf(lat));
        p.setLongitude(BigDecimal.valueOf(lon));
        p.setGatewayNm(gatewayNm);
        return p;
    }
}
