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
 * @param remaining  what is left of the daily allowance; null on a plan with no daily cap
 * @param dailyLimit the plan's ceiling. Brevo publishes only the remainder, so this is
 *                   {@code sent + remaining} — derived rather than hardcoded to 300, so it
 *                   stays right on a paid plan and if the free tier ever changes
 * @param error      why the figures are absent, when Brevo could not be reached. Present
 *                   only alongside null figures — a reason to show instead of numbers
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BrevoUsageResponse(Integer sent, Integer remaining, Integer dailyLimit, String error) {
}
