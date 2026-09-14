package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Vessel types that are not one of the categories, and the category each one maps to.
 *
 * @param applied   false for the dry run, true when {@code remapped} has been written
 * @param changeSet the History tab's description of the write
 * @param remapped  one-off wordings with the category they name; the wording itself is kept in
 *                  the vessel's notes when written
 * @param unmapped  wordings that name no category — listed, never changed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselTypeCheckResponse(boolean applied,
                                      String changeSet,
                                      List<Remap> remapped,
                                      List<Unmapped> unmapped) {

    public record Remap(Long vesselId, String vesselName, String before, String after) {
    }

    public record Unmapped(Long vesselId, String vesselName, String value) {
    }
}
