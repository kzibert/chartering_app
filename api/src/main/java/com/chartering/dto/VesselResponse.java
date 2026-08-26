package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselResponse(
        Long id,
        String name,
        String imoNumber,
        BigDecimal deadweightTonnage,
        BigDecimal deadweightCargoCapacity,
        BigDecimal grainCapacityM3,
        BigDecimal baleCapacityM3,
        BigDecimal maximumDraft,
        Integer yearBuilt,
        String vesselType,
        String flag,

        // What a charterer asks before anything else. All nullable, and null means "not on
        // file" rather than "no" - false would be a claim about four thousand unchecked rows.
        Boolean geared,
        String gearDescription,
        Short holds,
        Short hatches,
        Boolean grainFitted,
        Boolean timberFitted,
        Boolean imoFitted,
        String iceClass,

        /**
         * Names she used to carry, so a position list using one of them still finds her.
         * Sent with the row rather than fetched per vessel: this is how a search result
         * explains why a ship nobody asked for by that name is in the list.
         */
        List<VesselExNameResponse> exNames,
        Long ownerId,
        String ownerName,
        String notes,
        boolean confirmed,
        OffsetDateTime confirmedAt,
        String confirmedBy,
        String confirmNotes,
        boolean banned,
        boolean legacy) {
}
