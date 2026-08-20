package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * The day's outgoing volume, for the counter on the Circulars tab.
 *
 * <p>Exists because the pacing rails protect the mailbox's <em>per-hour</em> allowance and
 * nothing watches the <em>daily</em> one — and exceeding a provider's daily cap can suspend
 * outgoing mail on the whole account. This is the number to read before starting another
 * circular.
 *
 * @param date          the local day being reported, so a tab left open overnight can tell
 *                      that its counter has rolled over
 * @param sent          addresses actually mailed today, across every circulation
 * @param circulations  how many circulations those messages came from — counted over the
 *                      same rows as {@code sent}, so it is the circulations that
 *                      <em>delivered</em> today, not the ones opened today
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationTodayResponse(LocalDate date, int sent, int circulations) {
}
