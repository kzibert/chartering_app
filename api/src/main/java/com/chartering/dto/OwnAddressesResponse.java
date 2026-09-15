package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The desk's own email addresses, lower-cased, in the order they were entered. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OwnAddressesResponse(List<String> addresses) {
}
