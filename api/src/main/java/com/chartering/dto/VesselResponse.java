package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
        Long ownerId,
        String ownerName,
        boolean confirmed,
        OffsetDateTime confirmedAt,
        String confirmedBy,
        String confirmNotes) {
}
