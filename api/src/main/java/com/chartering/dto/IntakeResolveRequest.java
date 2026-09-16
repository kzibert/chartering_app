package com.chartering.dto;

import com.chartering.service.parser.IntakeService;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

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

    /**
     * VESSEL_FIELDS only: a value the reviewer typed instead of either side's.
     *
     * <p><b>The third answer the screen was missing.</b> The two it had were the record and the
     * email, and a broker's list is regularly wrong in a way that does not make the record
     * right - a capacity quoted in the wrong unit, a gear description garbled, a flag out of
     * date on both sides. Answering that took two visits, and the second half is the one that
     * gets forgotten.
     *
     * <p>Keyed by field, and a field named here must also be in {@link #fields} (or the list
     * must be empty, meaning all of them): a correction to a row that is not being accepted
     * would be a write nobody asked for. The text is read into the field's own type and a value
     * that cannot be one is refused with the field's name in the message, rather than skipped.
     *
     * <p>A correction is also what stops the question coming back. The email is as wrong
     * tomorrow as it is today, so the value it reported is recorded as declined against the
     * firms that sent it - see {@code IntakeFieldDecision}.
     */
    private Map<String, String> corrections;

    /** NEW_VESSEL with ALTERNATIVE: the hull this position actually belongs to. */
    private Long vesselId;

    /** Optional. Replaces the summary the service would have written on the item. */
    private String note;
}
