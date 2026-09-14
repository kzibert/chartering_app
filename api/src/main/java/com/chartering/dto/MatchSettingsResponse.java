package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The matching rule's runtime settings, with what "reset" would restore.
 *
 * <p>The defaults travel with the values for the reason the parser's and the circulation
 * settings do: a screen offering "reset to defaults" has to be able to say what they are, and
 * a second call to find out is a second call that can disagree with the first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchSettingsResponse(
        double ballastSpeedKnots,
        int portAllowanceHours,
        int minUtilisationPercent,
        int idealUtilisationPercent,
        int idealBallastDays,
        int maxBallastDays,
        double defaultBallastSpeedKnots,
        int defaultPortAllowanceHours,
        int defaultMinUtilisationPercent,
        int defaultIdealUtilisationPercent,
        int defaultIdealBallastDays,
        int defaultMaxBallastDays) {
}
