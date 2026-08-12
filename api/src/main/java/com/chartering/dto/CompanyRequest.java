package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
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
    private String notes;
}
