package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hold capacities checked against each ship's size: the figures that are cubic feet stored in
 * the cubic-metre columns, and the figures no unit explains.
 *
 * @param applied    false for the dry run, true when {@code converted} has been written
 * @param changeSet  the History tab's description of the write, so the operation can be found
 *                   there and any one figure reverted
 * @param converted  figures plausible only as cubic feet, with the cubic metres they become
 * @param implausible figures that fit neither unit — too small for the hull, or between the two
 *                   bands. Listed, never changed: no rule can say what they were meant to be
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselCapacityCheckResponse(boolean applied,
                                          String changeSet,
                                          List<Conversion> converted,
                                          List<Implausible> implausible) {

    /** @param perTonne the figure per tonne of deadweight, as stored — what decided it */
    public record Conversion(Long vesselId, String vesselName, String field, BigDecimal deadweight,
                             BigDecimal before, BigDecimal after, BigDecimal perTonne) {
    }

    public record Implausible(Long vesselId, String vesselName, String field, BigDecimal deadweight,
                              BigDecimal value, BigDecimal perTonne) {
    }
}
