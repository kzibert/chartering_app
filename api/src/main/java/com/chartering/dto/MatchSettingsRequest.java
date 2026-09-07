package com.chartering.dto;

import lombok.Data;

/**
 * The four numbers the matching rule cannot derive.
 *
 * <p>Every field is optional so the Settings tab can send one without holding the others,
 * matching how the rest of that screen behaves. The service validates the ranges — and the
 * two utilisation figures against each other — and says what they should be rather than
 * clamping silently, because a floor quietly moved is a setting that looks saved and is not.
 */
@Data
public class MatchSettingsRequest {

    /** Knots. What a ballast leg in miles is turned into days at. */
    private Double ballastSpeedKnots;

    /** Hours added to every passage for getting off one berth and alongside another. */
    private Integer portAllowanceHours;

    /**
     * Percent. Below this share of the ship filled, a pairing is ruled out.
     *
     * <p>The answer to "do not offer a 14,000-tonner for a 4,000-tonne cargo": she can lift
     * it, which is exactly the problem, and freight is earned by the tonne while the ship is
     * paid for whole.
     */
    private Integer minUtilisationPercent;

    /** Percent. At or above this, the intake check scores full marks. */
    private Integer idealUtilisationPercent;
}
