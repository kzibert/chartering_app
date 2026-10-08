package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for POST /api/v1/vocabulary/aliases. */
@Data
public class DeskAliasRequest {

    @NotBlank(message = "is required")
    @Pattern(regexp = "PORT|AREA", message = "must be PORT or AREA")
    private String kind;

    /** The port's or the trade area's id. */
    @NotNull(message = "is required")
    private Long targetId;

    @NotBlank(message = "is required")
    @Size(max = 100, message = "must be at most 100 characters")
    private String alias;
}
