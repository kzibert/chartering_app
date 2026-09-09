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
        VesselLastPositionResponse lastPosition,

        /**
         * The last web search run from her own record, if any — the candidate, the evidence,
         * and what it would change.
         *
         * <p>Here rather than on an endpoint of its own for the reason {@code lastPosition} is:
         * it is one indexed row, it is part of what the screen showing her is for, and a second
         * call would mean the card flickers in after the record it belongs to. Absent when
         * nothing has been searched for her, and always absent where {@code LOOKUP_ENABLED} is
         * off — the feature is not part of that deployment, so the card does not appear.
         *
         * <p>Searches raised by the review queue are deliberately not here: those belong to the
         * email that caused them, and showing one on her record would present a question asked
         * about a circular three weeks ago as something somebody just ran.
         */
        VesselLookupResponse lookup) {
}
