package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A water a broker quotes tonnage and cargo in: the Black Sea, the West Med, the Adriatic.
 *
 * <p><b>Not a {@link Region}.</b> That table is a circulation-targeting list — its rows
 * include "Europe ports EXCLUDED" and "Israel - no", which are decisions about who to mail
 * rather than places. This one answers a different question: can this ship reach that cargo
 * in time. Every row here is somewhere on the water, and the rows nest.
 *
 * <p>{@link #parent} is containment and only containment. West Med's parent is the
 * Mediterranean because a vessel open in the West Med <em>is</em> in the Med, so a cargo
 * asking for the Med finds her. It says nothing about the West Med being near the East Med;
 * that is {@link TradeAreaDistance}, and keeping the two apart is what stops "inside" and
 * "next door" being confused for one another.
 */
@Getter
@Setter
@Entity
@Table(name = "trade_areas")
public class TradeArea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The short form the app speaks internally: BSEA, WMED, ADR. Unique. */
    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private TradeArea parent;

    /** Dropdown order; groups the ranges as the market quotes them rather than A-Z. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(columnDefinition = "text")
    private String notes;
}
