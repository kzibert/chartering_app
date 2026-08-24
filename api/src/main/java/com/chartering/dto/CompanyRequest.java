package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CompanyRequest {

    @NotBlank(message = "name is required")
    private String name;

    private boolean shipowner;
    private boolean charterer;
    private boolean broker;
    private boolean agent;
    private boolean solo;
    private String cityName;

    @Size(max = 100, message = "country must be at most 100 characters")
    private String country;

    /** Stored bare — "fednav.com". The scheme is added when a link is made, not here. */
    @Size(max = 255, message = "website must be at most 255 characters")
    private String website;

    private String notes;
}
