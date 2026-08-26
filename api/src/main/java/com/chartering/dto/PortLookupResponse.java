package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A port for the dropdowns, with the water it sits on.
 *
 * <p>Wider than {@link LookupResponse} by two fields and deliberately not sharing it: the
 * area is what lets a cargo form show "Salerno (W.Med)" while the user is choosing, so the
 * consequence of picking a port for matching is visible at the moment of picking it. Regions
 * and tonnage categories have no equivalent and keep the plain shape.
 *
 * <p>{@code tradeAreaCode} is null for the dozen ports nothing has placed yet - which is
 * worth seeing rather than hiding, since it is exactly the list somebody should work through.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortLookupResponse(Long id, String name, Long tradeAreaId, String tradeAreaCode) {
}
