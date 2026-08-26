package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Recording what was done about one pairing. */
@Data
public class MatchOutcomeRequest {

    /** One of {@code MatchOutcome}: SHORTLISTED, OFFERED, DECLINED, FIXED, DISMISSED. */
    @NotBlank(message = "outcome is required")
    private String outcome;

    private String note;

    /**
     * The position this was decided against, so the decision reads back against what was
     * known at the time rather than wherever she is now.
     */
    private Long vesselPositionId;
}
