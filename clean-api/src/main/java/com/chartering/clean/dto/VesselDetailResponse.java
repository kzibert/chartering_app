package com.chartering.clean.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Vessel plus its owner company and that company's contacts (vessel -> companies path). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselDetailResponse(
        VesselResponse vessel,
        CompanyResponse owner,
        List<ContactResponse> ownerContacts) {
}
