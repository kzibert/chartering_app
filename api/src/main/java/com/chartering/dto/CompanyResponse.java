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
        boolean noWorkingEmail) {
}
