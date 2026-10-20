package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyResponse(
        Long id,
        String name,
        boolean shipowner,
        boolean charterer,
        boolean broker,
        boolean agent,
        /** one-person business; set by hand, never inferred */
        boolean solo,
        String cityName,
        String country,
        /** bare host as stored, with no scheme — the UI adds one to link it */
        String website,
        String notes,
        boolean confirmed,
        OffsetDateTime confirmedAt,
        String confirmedBy,
        String confirmNotes,
        boolean banned,
        boolean legacy,
        /** derived: the company has email addresses, but every one is flagged not working */
        boolean noWorkingEmail,
        /**
         * derived: a question about the firm's own details is waiting in Intake, minor or not.
         * The "Update from correspondence" mark. A primitive like the flags above, so it is always
         * sent: false is the answer for a firm with nothing waiting, not an absent field.
         */
        boolean correspondenceUpdate) {
}
