package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * The day's outgoing volume, broken down by the flow each message left through.
 *
 * <p>Exists because the pacing rails protect a provider's <em>per-hour</em> allowance and
 * nothing watches the <em>daily</em> one — and exceeding a daily cap can suspend outgoing
 * mail on the whole account. This is the number to read before starting another circular.
 *
 * <p>The two halves are counted differently, and have to be. The mailbox flow can only be
 * counted here, because SMTP offers no way to ask a mailbox what it has already sent today.
 * Brevo can be asked, and is — see {@link #brevo} — because its allowance is spent by
 * everything on the account, not only by this app.
 *
 * @param date          the local day being reported, so a tab left open overnight can tell
 *                      that its counter has rolled over
 * @param sent          addresses this app mailed today, across every circulation and both flows
 * @param circulations  how many circulations those messages came from — counted over the
 *                      same rows as {@code sent}, so it is the circulations that
 *                      <em>delivered</em> today, not the ones opened today
 * @param viaMailbox    of {@code sent}, how many went out from the mailbox over SMTP
 * @param viaBrevo      of {@code sent}, how many went out through the Brevo API
 * @param brevo         Brevo's own account-wide figures for today; null when no API key is
 *                      configured, since there is then no account to report on
 * @param mailbox       what the mailbox itself sent today, from its Sent folder, plus this
 *                      app's own replies. A third source with a third failure mode, and one
 *                      that must never be added to {@code viaMailbox} — see
 *                      {@link MailboxSendingResponse}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CirculationTodayResponse(LocalDate date, int sent, int circulations,
                                       int viaMailbox, int viaBrevo,
                                       BrevoUsageResponse brevo,
                                       MailboxSendingResponse mailbox) {
}
