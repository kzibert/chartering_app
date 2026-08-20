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
            Integer sent = sentOn(client, day);
            Integer remaining = remainingToday(client);
            // The plan's ceiling is not published as a number anywhere in the API — only what
            // is left of it — so it is reconstructed from the two figures that are. Doing it
            // this way rather than hardcoding 300 means it stays right on a paid plan, and
            // stays right if the free tier's allowance ever changes.
            Integer dailyLimit = sent != null && remaining != null ? sent + remaining : null;
            return new BrevoUsage(true, sent, remaining, dailyLimit, null);
        } catch (RuntimeException e) {
            String why = CircularSendException.rootMessage(e);
            log.warn("Could not read Brevo usage: {}", why);
            return new BrevoUsage(true, null, null, null, why);
        }
    }

    /**
     * Messages Brevo accepted today, from its own aggregated report.
     *
     * <p>{@code requests} rather than {@code delivered}: the allowance is spent when Brevo
     * takes the message, not when the recipient's server accepts it, so a bounce still counts
     * against the day. Counting deliveries would quietly overstate what is left.
     */
    private Integer sentOn(RestClient client, LocalDate day) {
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
        return report == null ? null : report.requests();
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
     * @param remaining  what is left of the daily allowance; null on a plan with no daily cap
     * @param dailyLimit sent + remaining; null whenever either half is
     * @param error      why the figures are missing, when Brevo could not be reached
     */
    public record BrevoUsage(boolean configured, Integer sent, Integer remaining,
                             Integer dailyLimit, String error) {

        static BrevoUsage notConfigured() {
            return new BrevoUsage(false, null, null, null, null);
        }

        /** True when there are real numbers to show, as opposed to a reason there are none. */
        public boolean hasFigures() {
            return configured && sent != null;
        }
    }

    // ---------------------------------------------------------------- wire types

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AggregatedReport(Integer requests, Integer delivered) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Account(List<Plan> plan) {
    }

    /** {@code credits} is a number in the JSON and may come back fractional on some plans. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Plan(String creditsType, Double credits) {
    }
}
