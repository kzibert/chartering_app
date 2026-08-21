package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One address on a list, with the mail-merge fields snapshotted when it was collected. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationListEntryResponse(
        Long id,
        Long contactId,
        String email,
        Long personId,
        String personName,
        String greetingName,
        String title,
        Long companyId,
        String companyName,
        /**
         * This address will be dropped when the circular goes out, because the person behind
         * it has left the company.
         *
         * <p>A list is a snapshot, so the row stays on it rather than vanishing from a
         * document the user prepared — but a row that cannot be mailed and does not say so
         * is a recipient count that lies. Matched on the address, exactly as the send-time
         * filter matches, so the page cannot promise something the send will not do.
         */
        boolean personLeft) {
}
