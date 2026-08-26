package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * How far one {@link TradeArea} is from another, in ballast days.
 *
 * <p>Days rather than miles because days are the unit the question is asked in: a broker
 * does not ask how far the ship is from the cargo, they ask whether she can be there inside
 * the laycan, and that is her open date plus this number.
 *
 * <p>The table is sparse on purpose. Only pairs this desk would actually consider are
 * seeded; an absent pair means "far enough that Match should say so rather than pretend to
 * know", which is a different and more honest answer than a large number.
 */
@Getter
@Setter
@Entity
@Table(name = "trade_area_distances")
@IdClass(TradeAreaDistance.Key.class)
public class TradeAreaDistance {

    @Id
    @Column(name = "from_area_id")
    private Long fromAreaId;

    @Id
    @Column(name = "to_area_id")
    private Long toAreaId;

    @Column(name = "ballast_days", nullable = false)
    private BigDecimal ballastDays;

    /** Composite key; both rows of a symmetric pair are stored, so lookups need no OR. */
    @Getter
    @Setter
    public static class Key implements Serializable {
        private Long fromAreaId;
        private Long toAreaId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(fromAreaId, k.fromAreaId) && Objects.equals(toAreaId, k.toAreaId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fromAreaId, toAreaId);
        }
    }
}
