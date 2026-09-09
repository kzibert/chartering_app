package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * One cargo and one ship, weighed against each other.
 *
 * <p>Both sides ride along in full. The Match screen is read while deciding whether to offer
 * a ship, and every figure that decision turns on — her size, her gear, where she is, what
 * the charterer asked for — has to be on the row rather than a click away.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchResponse(
        CargoResponse cargo,
        VesselPositionResponse position,

        /** 0-100: the share of the applicable weight that passed. */
        int score,
        /** At least one check FAILed — we hold data saying she does not fit. */
        boolean ruledOut,
        /** How many checks the cargo asked for that her record could not answer. */
        long unknowns,
        List<MatchCheckResponse> checks,

        /** The ballast leg, in days, when both ends are known and something connects them. */
        Double ballastDays,

        /**
         * The same leg in nautical miles, and present only when both ends named a berth the
         * sea network could place.
         *
         * <p>Absent is meaningful rather than missing: it says the days came from the
         * trade-area table — a broker's round figure between two waters — because one end of
         * the pairing was only ever quoted as a water. The check's own detail line says which
         * of the two answered, and this is what lets the compact row say so too.
         */
        Double ballastDistanceNm,

        /** When she could present at the load port, on those ballast days. */
        LocalDate earliestArrival,

        /**
         * What was already decided about this pairing, if anything — SHORTLISTED, OFFERED,
         * DECLINED, FIXED, DISMISSED. Present so the screen can grey out the four already
         * offered and the two the owner turned down on Tuesday, which is the difference
         * between a list that saves work and a list that is work.
         */
        String outcome,
        String outcomeNote) {
}
