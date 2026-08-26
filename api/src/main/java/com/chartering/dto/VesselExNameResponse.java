package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * A name a vessel used to carry.
 *
 * <p>{@code source} matters on screen and is not decoration: {@code backfill} rows were
 * extracted by a migration out of names people had typed a rename history into
 * ("LOIRE RIVER/ EX AMIKO"), and they are the ones to suspect first if a ship ever looks
 * wrong. Anything a person added since is {@code manual}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselExNameResponse(
        Long id,
        Long vesselId,
        String name,
        String source,
        LocalDate renamedAt,
        String notes) {
}
