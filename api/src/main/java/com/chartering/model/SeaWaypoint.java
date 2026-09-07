package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A point at sea, and one of two kinds.
 *
 * <p>Most are open water in the middle of something a ship crosses in a straight line — the
 * central Black Sea, the Tyrrhenian, the Arabian Sea. They exist so that the {@link SeaLeg}s
 * either side of them are legs whose water is genuinely open, which is what lets a leg carry
 * no distance of its own.
 *
 * <p>The rest are the narrows every route through a region has to pass: the Bosphorus, the
 * Dardanelles, Gibraltar, Suez, the Kerch Strait. Those carry {@link #delayHours}, and they
 * are why the graph is worth having at all rather than being a slower way to compute a
 * straight line. A bulker waiting off Kavak for a northbound Bosphorus convoy is not
 * sailing, and a laycan calculation that leaves the wait out is a day optimistic on every
 * Black Sea passage there is.
 */
@Getter
@Setter
@Entity
@Table(name = "sea_waypoints")
public class SeaWaypoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The short form: BOSN, DARD, CMEDX. Unique. */
    @Column(nullable = false, length = 24)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private BigDecimal latitude;

    @Column(nullable = false)
    private BigDecimal longitude;

    /**
     * Hours lost here over and above sailing through: a convoy wait, pilotage, a canal
     * transit. Zero on the open-water nodes, which is most of them.
     */
    @Column(name = "delay_hours", nullable = false)
    private BigDecimal delayHours = BigDecimal.ZERO;

    @Column(columnDefinition = "text")
    private String notes;
}
