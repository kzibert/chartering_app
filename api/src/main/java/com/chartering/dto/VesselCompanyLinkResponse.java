package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One company attached to a vessel, with the capacity it acts in.
 * Assembled on read from vessels.owner_id plus the broker link rows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselCompanyLinkResponse(
        Long companyId,
        String companyName,
        String cityName,
        /** 'owner' | 'exclusive_broker' | 'broker' */
        String role,
        String notes) {
}
