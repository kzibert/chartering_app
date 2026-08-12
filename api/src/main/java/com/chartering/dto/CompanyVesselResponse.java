package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A vessel a company is attached to, and in what capacity — one row of its Vessels tab. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyVesselResponse(
        VesselResponse vessel,
        /** 'owner' | 'exclusive_broker' | 'broker' */
        String role) {
}
