package com.chartering.clean.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class VesselRequest {

    @NotBlank(message = "name is required")
    private String name;

    private String imoNumber;
    private BigDecimal deadweightTonnage;
    private BigDecimal deadweightCargoCapacity;
    private BigDecimal grainCapacityM3;
    private BigDecimal baleCapacityM3;
    private BigDecimal maximumDraft;
    private Integer yearBuilt;
    private String vesselType;
    private String flag;
    private Long ownerId;
    private String notes;
}
