package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A person together with their emails and phones — one row of the people search.
 * Carrying the contacts inline is what lets the page group by person without a
 * follow-up request per expanded row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonDetailResponse(
        PersonResponse person,
        List<ContactResponse> contacts) {
}
