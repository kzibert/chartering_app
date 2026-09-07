package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "ports")
public class Port {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private Region region;

    /**
     * Which water this port sits on, for matching.
     *
     * <p>Not the same thing as {@link #region} and not a replacement for it: a region here
     * is a circulation-targeting bucket ("Europe ports EXCLUDED"), while this answers "can a
     * ship open in the West Med reach this berth". Nullable, and around a dozen of the ports
     * on file have no area yet — a port without one still works everywhere it worked before.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trade_area_id")
    private TradeArea tradeArea;

    // ---- where the berth actually is ----
    // The area above answers "which water"; these answer "how far", which is a different
    // question and the one a laycan turns on. Both are needed: an area is what a broker's
    // email gives, a coordinate is what a distance is computed from.

    private BigDecimal latitude;

    private BigDecimal longitude;

    /**
     * ISO 3166-1 alpha-2. How "Tripoli" gets disambiguated on screen, and how a parsed
     * "Casablanca, Morocco" is confirmed rather than assumed. Two letters rather than a
     * name, so it cannot drift into three spellings of one country.
     */
    @Column(length = 2)
    private String country;

    /**
     * The open-water point this berth is reached from, and the whole of its connection to
     * the route network.
     *
     * <p>Nullable, and null is the ordinary state for a port nobody has placed — the
     * estimate then falls back to the trade-area table, exactly as it did before any of this
     * existed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gateway_waypoint_id")
    private SeaWaypoint gatewayWaypoint;

    /**
     * The real distance to that gateway, where the straight line is not it.
     *
     * <p>Null means "compute the great circle", which is right for a coastal berth a few
     * miles off the lane. It is filled for the river ports and nothing else: Rostov is 250
     * miles up the Don and across the Azov, Izmail 50 miles up the Danube, Kiev most of the
     * way across Ukraine. No coordinate pair could say that.
     */
    @Column(name = "gateway_nm")
    private BigDecimal gatewayNm;

    @Column(name = "legacy_id")
    private Long legacyId;
}
