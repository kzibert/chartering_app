package com.chartering.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler: the mailbox poller, and the timers that run only where there is a
 * local parser (the sweep, the reconcile pass, the board fetch) or a lookup provider. They share
 * Spring's one scheduler thread, which is enough because each hands its real work to a worker of
 * its own and returns.
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
