package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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

    @Column(name = "legacy_id")
    private Long legacyId;
}
