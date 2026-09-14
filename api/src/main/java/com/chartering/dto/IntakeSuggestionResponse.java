package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One hull a {@code NEW_VESSEL} item offers as "could she be this one", with enough of her
 * record to answer the question on the spot.
 *
 * <p><b>The identity half is the payload's and the particulars half is the database's, and
 * that split is deliberate.</b> {@code name}, {@code imoNumber} and {@code reason} are read
 * back out of the item, because they are a record of the search that ran — the figures the
 * comparison actually weighed, on the day it weighed them. {@code vessel} is fetched now, so
 * a hull whose deadweight was filled in last week is offered with the figure she carries
 * rather than the one she carried when the email arrived. An item can sit in this queue for
 * weeks; a snapshot of a fleet that has moved is the thing a reviewer would be checking
 * against a screen they have open in the other tab.
 *
 * @param vessel her record as it stands, or null where she has been deleted since the item
 *               was raised. Null rather than dropping the row: the payload still says a hull
 *               of that name was suggested and why, and silently shortening the list would
 *               make the count on the queue row disagree with the drawer
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntakeSuggestionResponse(
        Long vesselId,
        String name,
        String imoNumber,
        /** Why she is being offered, in figures: "DWT 28,500 against 28,400, built 2003". */
        String reason,
        VesselResponse vessel) {
}
