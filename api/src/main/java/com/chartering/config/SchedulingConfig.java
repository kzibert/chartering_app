package com.chartering.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler, for the one thing that needs it: the mailbox poller.
 *
 * <p>Circulars deliberately do not run on this — a campaign owns its own single worker
 * thread so that pacing, pause and resume are decided by the run itself rather than by a
 * shared pool (see {@code EmailCampaignService}). Reading the inbox is the opposite kind of
 * job: fixed cadence, no state to carry, nothing to resume.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
