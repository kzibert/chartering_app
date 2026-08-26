package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A trade area for the dropdowns and for the vocabulary screen.
 *
 * <p>Carries the parent as well as the id, because the dropdown groups by it: showing
 * twenty-seven waters as one flat alphabetical list puts the Adriatic three rows above the
 * Baltic and hides that both are inside ranges a broker thinks of together.
 *
 * <p>The aliases come with it. They are the part of this vocabulary that is actually
 * maintained - somebody will eventually add "TYRRHENIAN" - and a screen that lists areas
 * without showing what each already answers to invites the same alias being added twice.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeAreaResponse(
        Long id,
        String code,
        String name,
        Long parentId,
        String parentCode,
        int sortOrder,
        String notes,
        List<String> aliases) {
}
