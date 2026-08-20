package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContactResponse(
        Long id,
        Long personId,
        String personName,
        String title,
        String greetingName,
        Long companyId,
        String companyName,
        String contactKind,
        String contactValue,
        String notes,
        boolean confirmed,
        OffsetDateTime confirmedAt,
        String confirmedBy,
        String confirmNotes,
        boolean banned,
        boolean legacy,
        boolean main,
        boolean working,
        /** flagged for use in circulations — see RecipientSelectionService for the rule */
        boolean circ,
        /**
         * flagged never to be circulated to. Distinct from {@code working}: the address is
         * fine and still the one to write to by hand, it is only bulk mail it is kept out of.
         */
        boolean noCirc,
        /**
         * this number is on WhatsApp, as confirmed by hand from the wa.me check. Phone
         * contacts only — see {@code ContactService#setHasWhatsapp}.
         */
        boolean hasWhatsapp) {
}
