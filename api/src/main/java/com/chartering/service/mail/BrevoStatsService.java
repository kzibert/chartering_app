package com.chartering.service.mail;

import com.chartering.config.BrevoProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What Brevo says about today: how much has gone out through it, and how much of the plan's
 * daily allowance is left.
 *
 * <p>Read from Brevo rather than counted locally on purpose. The app knows only what it sent
 * itself, and the allowance is consumed by everything on the account — a campaign sent from
 * Brevo's own dashboard, another integration, a test from their UI. A local count would
 * therefore say "you have plenty left" right up until the send that gets refused. Brevo's own
 * figures are the ones the daily cap is actually enforced against.
 *
 * <p>Two figures come back and they are deliberately not combined. The remainder is live and
 * authoritative; the day's statistics are an after-the-fact report that counts accepted
 * messages, including ones that cost nothing, and lags a running campaign by minutes. They
 * answer different questions and are shown as two numbers, against a ceiling that is
 * configured — see {@link BrevoProperties#getDailyLimit()} for why deriving it did not work.
 *
 * <p>The mailbox flow has no equivalent: SMTP offers no way to ask "how many have I sent
 * today", which is exactly why the local day counter exists in the first place. So the two
 * halves of the breakdown come from different places by necessity, not by oversight.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrevoStatsService {

    /**
     * How long an answer is reused.
     *
     * <p>The Circulars tab polls while a campaign runs, and every poll asking Brevo twice
     * would spend the account's API rate limit on a number that changes at most once per
     * message. Half a minute is well inside "current enough to decide whether to press Send"
     * and turns a poll-per-second screen into two calls a minute.
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** Brevo's plan entry for a per-day send ceiling, as opposed to purchased credits. */
    private static final String SEND_LIMIT = "sendLimit";

    private final BrevoProperties brevo;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    /** Last day a stale {@code daily-limit} was complained about; see {@code ceilingFor}. */
    private final AtomicReference<LocalDate> warnedAbout = new AtomicReference<>();

    /**
     * Today's Brevo figures, or empty when Brevo is not configured.
     *
     * <p>Never throws. This feeds a status panel beside a Send button — a reporting call
     * that cannot reach Brevo is worth a line saying so, and is never worth taking down the
     * screen that tells the user how much they have already sent today.
     */
    public BrevoUsage today() {
        if (brevo.getApiKey() == null || brevo.getApiKey().isBlank()) {
            return BrevoUsage.notConfigured();
        }
        LocalDate day = LocalDate.now();
        Cached hit = cache.get();
        if (hit != null && hit.day().equals(day) && hit.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return hit.usage();
        }
        BrevoUsage fresh = fetch(day);
        cache.set(new Cached(day, Instant.now(), fresh));
        return fresh;
    }

    private BrevoUsage fetch(LocalDate day) {
        try {
            RestClient client = newClient();
            Report report = reportFor(client, day);
            Integer remaining = remainingToday(client);
            return new BrevoUsage(true, report.requests(), report.blocked(), remaining,
                    ceilingFor(remaining, day), null);
        } catch (RuntimeException e) {
            String why = CircularSendException.rootMessage(e);
            log.warn("Could not read Brevo usage: {}", why);
            return new BrevoUsage(true, null, null, null, null, why);
        }
    }

    /**
     * The plan's daily ceiling, or null when there is no meaningful one to quote.
     *
     * <p>Read from configuration rather than reconstructed from the two figures Brevo does
     * publish. That reconstruction — today's statistics plus what is left — looked sound and
     * was not: {@code requests} counts what Brevo accepted and the allowance is spent by what
     * it sends, so every blocked address added one to the "ceiling", and the report's lag
     * moved it by tens mid-campaign. A denominator that drifts is worse than no denominator.
     *
     * <p>Null when the account reports no remainder — a plan on purchased credits has no
     * daily cap, and a ceiling without a remainder is half of a fraction. Null too when the
     * configured limit is zero or less, which is how that plan says so explicitly.
     */
    private Integer ceilingFor(Integer remaining, LocalDate day) {
        int limit = brevo.getDailyLimit();
        if (remaining == null || limit <= 0) {
            return null;
        }
        // Only reachable when the plan has grown past what is configured, since the remainder
        // is Brevo's own and the ceiling is ours. Warned once a day rather than on every cache
        // miss: it is a standing misconfiguration, not an event.
        if (remaining > limit && !day.equals(warnedAbout.getAndSet(day))) {
            log.warn("Brevo reports {} sends left today but chartering.brevo.daily-limit is {}"
                    + " — set BREVO_DAILY_LIMIT to this plan's real daily allowance.", remaining, limit);
        }
        return limit;
    }

    /**
     * Today's aggregated report, as Brevo tells it.
     *
     * <p>{@code requests} rather than {@code delivered}: the allowance is spent when Brevo
     * takes the message, not when the recipient's server accepts it, so a bounce still counts
     * against the day. Counting deliveries would quietly understate what has been used.
     *
     * <p>It is not the same quantity as the spent allowance, though, which is why this figure
     * is reported beside the remainder and never used to compute it. {@code blocked} comes
     * back alongside for exactly that reason: a suppressed address is counted as a request
     * here but costs nothing, and it is the usual explanation when the two disagree by a
     * little. The report is also aggregated after the fact and trails a running campaign by
     * minutes — fine for "how has today gone", useless as an input to arithmetic.
     */
    private Report reportFor(RestClient client, LocalDate day) {
        String iso = day.toString();
        AggregatedReport report = client.get()
                .uri(builder -> builder.path("/smtp/statistics/aggregatedReport")
                        .queryParam("startDate", iso)
                        .queryParam("endDate", iso)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("statistics: " + BrevoCircularSender.describe(res));
                })
                .body(AggregatedReport.class);
        return report == null ? new Report(null, null) : new Report(report.requests(), report.blocked());
    }

    /** The two figures from the day's report that the panel has a use for. */
    private record Report(Integer requests, Integer blocked) {
    }

    /**
     * What is left of the daily allowance, from the plan block on the account.
     *
     * <p>Brevo reports the remainder, not the usage. Only a {@code sendLimit} entry is a
     * per-day ceiling; a plan carrying purchased credits instead has no daily cap to report,
     * and inventing one from a credit balance would be a different number wearing this one's
     * label.
     */
    private Integer remainingToday(RestClient client) {
        Account account = client.get()
                .uri("/account")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("account: " + BrevoCircularSender.describe(res));
                })
                .body(Account.class);
        if (account == null || account.plan() == null) {
            return null;
        }
        return account.plan().stream()
                .filter(p -> p.creditsType() != null
                        && SEND_LIMIT.equalsIgnoreCase(p.creditsType().trim())
                        && p.credits() != null)
                .map(p -> (int) Math.round(p.credits()))
                .findFirst()
                .orElse(null);
    }

    private RestClient newClient() {
        return RestClient.builder()
                .baseUrl(brevo.getBaseUrl())
                .defaultHeader("api-key", brevo.getApiKey().trim())
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** One fetch, pinned to the day it describes so a rollover past midnight is never served. */
    private record Cached(LocalDate day, Instant fetchedAt, BrevoUsage usage) {
    }

    /**
     * Today through Brevo's eyes.
     *
     * @param configured false when no API key is set, in which case nothing else is populated
     * @param sent       messages Brevo accepted today, from every source on the account
     * @param blocked    of those, how many were suppressed rather than sent — they cost no
     *                   allowance, so they are why {@code sent} can exceed what the day spent
     * @param remaining  what is left of the daily allowance; null on a plan with no daily cap
     * @param dailyLimit the plan's ceiling, from configuration; null when there is no daily cap
     * @param error      why the figures are missing, when Brevo could not be reached
     */
    public record BrevoUsage(boolean configured, Integer sent, Integer blocked, Integer remaining,
                             Integer dailyLimit, String error) {

        static BrevoUsage notConfigured() {
            return new BrevoUsage(false, null, null, null, null, null);
        }

        /** True when there are real numbers to show, as opposed to a reason there are none. */
        public boolean hasFigures() {
            return configured && sent != null;
        }
    }

    // ---------------------------------------------------------------- wire types

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AggregatedReport(Integer requests, Integer delivered, Integer blocked) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Account(List<Plan> plan) {
    }

    /** {@code credits} is a number in the JSON and may come back fractional on some plans. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Plan(String creditsType, Double credits) {
    }
}
