package com.chartering.dto;

import com.chartering.service.parser.IntakeService;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * A decision about one review item.
 *
 * <p>One request shape for all three kinds, because the alternative is three endpoints that
 * do the same thing to the same row. Which fields matter depends on the kind, and the
 * service says so plainly when a required one is missing — {@code ALTERNATIVE} on a new
 * vessel without a {@code vesselId} is "choose the vessel this position belongs to", not a
 * validation error about a null.
 */
@Data
public class IntakeResolveRequest {

    /**
     * ACCEPT, ALTERNATIVE or DISCARD.
     *
     * <p>The middle one is not a rejection and is named so it cannot be read as one: on two
     * of the three kinds it also writes. Keeping a cargo separate creates it; linking a new
     * vessel to an existing hull files the position against that hull and remembers the name
     * the email used. Only DISCARD writes nothing.
     */
    @NotNull(message = "action is required")
    private IntakeService.Action action;

    /**
     * VESSEL_FIELDS only: which fields to write.
     *
     * <p>Empty or absent means all of them, which is what "Accept all" sends. The UI never
     * sends an empty list meaning "none" — that would be an accept that quietly did nothing,
     * and the button for it is Discard.
     */
    private List<String> fields;

    /** NEW_VESSEL with ALTERNATIVE: the hull this position actually belongs to. */
    private Long vesselId;

    /** Optional. Replaces the summary the service would have written on the item. */
    private String note;
}
