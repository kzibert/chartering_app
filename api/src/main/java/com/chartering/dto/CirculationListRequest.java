package com.chartering.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create or rename a saved list. Entries are managed through the list's own endpoints
 * rather than being replaced wholesale here, so renaming a 300-address list is a rename.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CirculationListRequest {

    @Size(max = 150, message = "name must be at most 150 characters")
    private String name;

    private String notes;
}
