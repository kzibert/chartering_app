package com.chartering.dto;

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

    // Nullable on purpose: leaving one out says "still not on file", which is a different
    // statement from false and the only honest one for most of the fleet.
    private Boolean geared;
    private String gearDescription;
    private Short holds;
    private Short hatches;
    private Boolean grainFitted;
    private Boolean timberFitted;
    private Boolean imoFitted;
    private String iceClass;
    private Long ownerId;
    private String notes;
}
