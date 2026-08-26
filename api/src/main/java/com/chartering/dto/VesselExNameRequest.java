package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

/**
 * A former name being recorded by hand.
 *
 * <p>Only the name is required. The date she was renamed is almost never known — the old
 * name reached us in a circular, the day it stopped being current did not — and demanding
 * it would mean either a blocked form or a made-up date.
 */
@Data
public class VesselExNameRequest {

    @NotBlank(message = "name is required")
    private String name;

    private LocalDate renamedAt;
    private String notes;
}
