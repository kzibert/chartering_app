package com.chartering.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Which of an outside source's figures to believe.
 *
 * <p>Named explicitly rather than defaulting to all of them: this writes another database's
 * word onto a record somebody here may have checked, and "accept everything" is not a thing
 * that should be one careless click. The empty list is refused for the same reason — it would
 * be an accept that quietly did nothing.
 */
@Data
public class ApplyLookupRequest {

    @NotEmpty(message = "Tick at least one field to take from the lookup.")
    private List<String> fields;

    /**
     * The hull to write to.
     *
     * <p>Needed only on a {@code NEW_VESSEL} item, where the record does not exist until the
     * item has been accepted. On a {@code VESSEL_FIELDS} item the vessel is already known and
     * this is ignored.
     */
    private Long vesselId;
}
