package com.chartering.clean.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Company plus its contacts and the vessels it owns. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyDetailResponse(
        CompanyResponse company,
        List<ContactResponse> contacts,
        List<VesselResponse> vessels) {
}
