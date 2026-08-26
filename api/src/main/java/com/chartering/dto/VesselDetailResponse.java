package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Vessel plus every company attached to it (vessel -> companies path).
 *
 * {@code owner} and {@code ownerContacts} are the owner specifically — the contacts a
 * circular would reach. {@code links} is the full picture including brokers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselDetailResponse(
        VesselResponse vessel,
        CompanyResponse owner,
        List<ContactResponse> ownerContacts,
        List<VesselCompanyLinkResponse> links,
        /**
         * The most recent position reported about her, of any status — "last open".
         * Absent when nobody has ever reported one.
         */
        VesselLastPositionResponse lastPosition) {
}
