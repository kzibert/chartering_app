package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Open water between two {@link SeaWaypoint}s.
 *
 * <p>A row here asserts one thing: a ship sails between these two points in a straight line.
 * That is what makes {@link #distanceNm} optional — the great circle between the two
 * coordinate pairs <em>is</em> the distance, and a column of hand-typed numbers saying the
 * same thing would be a hundred more chances to be wrong and a hundred rows to remember when
 * a waypoint moves.
 *
 * <p>The override is for the legs that are not straight, and they are exactly the ones a
 * glance at a chart identifies: a strait that winds (the Bosphorus is seventeen miles of
 * river bends), a canal, a passage that has to follow a coast round a headland.
 *
 * <p>Symmetric, with both directions stored, the same choice {@link TradeAreaDistance} made
 * and for the same reason.
 */
@Getter
@Setter
@Entity
@Table(name = "sea_legs")
@IdClass(SeaLeg.Key.class)
public class SeaLeg {

    @Id
    @Column(name = "from_waypoint_id")
    private Long fromWaypointId;

    @Id
    @Column(name = "to_waypoint_id")
    private Long toWaypointId;

    /** Null means "the great circle between the two points", which is the common case. */
    @Column(name = "distance_nm")
    private BigDecimal distanceNm;

    @Column(columnDefinition = "text")
    private String notes;

    @Getter
    @Setter
    public static class Key implements Serializable {
        private Long fromWaypointId;
        private Long toWaypointId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(fromWaypointId, k.fromWaypointId)
                    && Objects.equals(toWaypointId, k.toWaypointId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fromWaypointId, toWaypointId);
        }
    }
}
