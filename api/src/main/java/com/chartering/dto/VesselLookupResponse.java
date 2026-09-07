package com.chartering.dto;

import com.chartering.service.lookup.LookupFields;
import com.chartering.service.lookup.VesselParticulars;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * What an outside source said about a hull, and what it would change if believed.
 *
 * <p>Everything here is a proposal. The screen shows the candidate, the evidence for it and
 * the page it came off, and a person ticks the fields they accept — which is a separate
 * action from accepting the email's figures precisely so the two origins stay apart in the
 * change log.
 *
 * @param status         OK, NO_MATCH or FAILED. NO_MATCH is a result, not an error: much of
 *                       this fleet is small tonnage a public database may not carry
 * @param confidence     0-100, the share of the available evidence that agreed. Low is not
 *                       wrong, it is unverified — an email that gave only a name leaves
 *                       nothing to corroborate with
 * @param reasons        what matched, in figures, so the reader can judge rather than trust
 * @param disagreements  what did not. Shown as prominently as the agreements: a candidate
 *                       that is right about the name and wrong about the year is the one
 *                       worth catching
 * @param onFileVesselId a hull already here carrying this IMO. The most valuable thing a
 *                       lookup can return — she is not new, she has been renamed
 * @param proposals      per field: what is on file, what the source says, and whether they
 *                       differ. Filling a blank is ordinary; overwriting a checked figure is
 *                       not, and the flag says which is which
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VesselLookupResponse(
        Long id,
        String provider,
        String query,
        String status,
        Integer confidence,
        /**
         * Whether anything beyond the name agreed.
         *
         * <p>The distinction the percentage cannot make on its own. The name is what was
         * searched for, so a candidate agreeing on it is the query coming back rather than
         * evidence — a hull matched on the name alone scores 100% and is entirely
         * unverified. The screen says so instead of letting the number speak.
         */
        Boolean corroborated,
        String sourceUrl,
        String error,
        OffsetDateTime fetchedAt,
        VesselParticulars matched,
        List<String> reasons,
        List<String> disagreements,
        Long onFileVesselId,
        String onFileVesselName,
        List<LookupFields.Proposal> proposals,
        List<VesselParticulars> candidates) {
}
