package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Today as Brevo itself reports it: what the account has spent and what is left of the
 * plan's daily allowance.
 *
 * <p>Account-wide, not app-wide. It counts anything sent on the key's account — a campaign
 * launched from Brevo's dashboard, another integration, a test from their UI — which is
 * precisely why it is worth showing beside this app's own count. A local tally would read
 * "plenty left" right up to the send Brevo refuses.
 *
 * <p>Every figure is nullable because every one of them can genuinely be unavailable: Brevo
 * may be unreachable, and a plan carrying purchased credits rather than a daily ceiling has
 * no {@code remaining} to report at all. A missing number is left missing rather than
 * defaulted to zero, which would read as "nothing left".
 *
 * @param sent       messages Brevo accepted today, across the whole account
 * @param blocked    of those, how many were suppressed rather than sent. They spend no
 *                   allowance, so they are the usual reason {@code sent} runs ahead of
 *                   {@code dailyLimit - remaining}; shown only to explain that gap
 * @param remaining  what is left of the daily allowance; null on a plan with no daily cap
 * @param dailyLimit the plan's ceiling, from {@code BREVO_DAILY_LIMIT}. Brevo publishes only
 *                   the remainder, and the obvious reconstruction of the rest —
 *                   {@code sent + remaining} — is not sound: the two count different events
 *                   and the statistics half lags mid-campaign, so the "ceiling" drifted
 * @param error      why the figures are absent, when Brevo could not be reached. Present
 *                   only alongside null figures — a reason to show instead of numbers
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BrevoUsageResponse(Integer sent, Integer blocked, Integer remaining,
                                 Integer dailyLimit, String error) {
}
