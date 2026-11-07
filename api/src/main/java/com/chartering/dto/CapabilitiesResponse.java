package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Which optional features this deployment has, as facts about the deployment alone.
 *
 * <p>Read from configuration and nothing else - no query, no network - so the one call the shell
 * makes at load is free on a cold instance. Each feature's own status endpoint still answers its
 * detailed questions (counts, reachability) on its own page.
 *
 * @param analysis      the Analysis tab: gathering and labelling training data
 * @param retrieval     an embedding server is configured, so the corpus can be indexed and shown
 *                      to the parser as examples (local only)
 * @param parserLocal   Intake reads with this deployment's own model, and its timers run
 * @param parserCloud   Intake may read with a hosted model under the person's own key
 * @param feed          the Feed tab, its fetching and summaries
 * @param lookup        looking a hull up on a public ship database
 * @param smtpBlocked   this host cannot open SMTP, so replies and circulars go out through Brevo
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilitiesResponse(
        boolean analysis,
        boolean retrieval,
        boolean parserLocal,
        boolean parserCloud,
        boolean feed,
        boolean lookup,
        boolean smtpBlocked) {
}
