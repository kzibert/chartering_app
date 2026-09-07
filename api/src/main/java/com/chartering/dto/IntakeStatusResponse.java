package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Whether this deployment reads its mail with a model, and how that is going.
 *
 * <p>The one endpoint behind the Intake tab that answers when the feature is off, for the
 * reason {@code AnalysisStatusResponse} does: it is what the UI asks before deciding whether
 * the tab is in the navigation, and a UI that had to discover a disabled feature by calling
 * something else and reading the 404 is a UI that shows an error toast on every page load.
 *
 * <p>Off, everything below {@code enabled} is null and dropped from the JSON — a deployment
 * that does not run this does not publish counts for a queue it is not keeping.
 *
 * @param reachable      whether the model server answered just now. Separate from
 *                       {@code enabled} because they fail differently and the cures are
 *                       different: one is a redeploy, the other is starting a container
 * @param unparsed       how much mail is waiting, which is what makes "Parse now" worth
 *                       pressing or not
 * @param nextSweepAt    null when the interval is 0 — the timer is off and the button is the
 *                       only way in, which the screen says in those words
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntakeStatusResponse(
        boolean enabled,
        Boolean reachable,
        String modelUrl,
        String reachabilityError,
        Boolean running,
        Long pendingItems,
        Long acceptedItems,
        Long rejectedItems,
        Long unparsed,
        Long parsedTotal,
        Long failedTotal,
        Integer sweepIntervalMinutes,
        Integer sweepBatchSize,
        /** How far back unparsed mail is fetched from. 0 = no limit. */
        Integer sweepMaxAgeDays,
        OffsetDateTime lastSweepAt,
        OffsetDateTime nextSweepAt,
        String lastSweepSummary,
        /** Things the user has to fix before this can do anything, worded as the next step. */
        List<String> warnings) {

    /** Off: nothing counted, nothing claimed. */
    public static IntakeStatusResponse disabled() {
        return new IntakeStatusResponse(false, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }
}
