package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.config.VesselLookupProperties;
import com.chartering.tenancy.TenantDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Re-reads the "is this a new ship?" queue against what the web has since answered.
 *
 * <p><b>Why it is a pass rather than a step inside the lookup.</b> The two answers arrive at
 * different times: an item is raised when a circular is read, and its number comes back
 * minutes or hours later when the lookup pass reaches it. Doing the conversion where the
 * lookup is stored would also mean {@link VesselLookupService} writing items and positions,
 * and it already depends on nothing of the sort — {@link IntakeService} depends on <em>it</em>,
 * so the call would be a cycle. A bean of its own, like {@link EmailParseRunner}, for the same
 * reason: the transaction has to go through the proxy.
 *
 * <p>Cheap when idle. The queue is a screen's worth of rows, not a table, and an item with no
 * lookup or no hull behind its number is two field reads and a skip.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntakeReconcileRunner {

    /**
     * Often enough to be there before somebody opens the drawer, rarely enough to be invisible.
     *
     * <p>Deliberately out of step with the lookup pass's own two minutes: this reads what that
     * one wrote, and the useful moment is shortly after it, not simultaneously with it.
     */
    private static final long TICK_MS = 150_000;

    /**
     * How often the waiting questions are weighed again, which is far less often than the tick.
     *
     * <p>Re-weighing compares every waiting item with the record it is about - the hull's field
     * reports, the firm's people and addresses - so it reads in proportion to the queue, on
     * every run, whether or not anything moved. On the hosted database that read is billed as
     * network transfer, and at the tick's pace it was most of what an idle installation sent
     * (2026-10-10, after the signature fix: about 0.06 MB a tick with six questions waiting).
     * What it keeps current is which sub-tab a question waits on, and that drifts with the
     * record over days, not minutes: half an hour late is invisible. The drawer weighs an item
     * again whenever it is opened anyway, so a person never acts on a stale verdict.
     */
    private static final Duration REWEIGH_EVERY = Duration.ofMinutes(30);

    private final IntakeService intake;
    private final ParserProperties parser;
    private final VesselLookupProperties lookups;
    private final TenantDirectory tenants;

    /** The last tick that re-weighed; the first tick after a start always does. */
    private volatile Instant lastReweigh = Instant.EPOCH;

    /** Each desk's queue in turn: a pass reads and writes one desk's items and records. */
    @Scheduled(fixedDelay = TICK_MS, initialDelay = 45_000)
    public void run() {
        if (!parser.isEnabled()) return;
        Instant now = Instant.now();
        boolean reweigh = !now.isBefore(lastReweigh.plus(REWEIGH_EVERY));
        tenants.forEachActive("Intake reconciliation", tenant -> runForDesk(reweigh));
        if (reweigh) lastReweigh = now;
    }

    private void runForDesk(boolean reweigh) {
        // Needs only the parser: which side of the queue a question sits on depends on the
        // record, and the record moves whether or not anything is being looked up.
        if (reweigh) {
            try {
                int moved = intake.reweighPending();
                if (moved > 0) log.info("Intake: {} waiting item(s) moved between the queue and Minor updates", moved);
            } catch (Exception e) {
                log.error("Intake re-weighing pass failed", e);
            }
        }
        // A sender becomes nameable by more doors than the company question - a contact typed
        // by hand, a message re-linked in the Mailbox - so the rows filed without one are asked
        // again on every tick. Mail is one indexed lookup per row whose message is now placed;
        // a board post is read again only when the desk's companies, people or addresses have
        // changed since it was last read (IntakeService.attributeUnreported).
        try {
            int named = intake.attributeUnreported();
            if (named > 0) log.info("Intake: {} position(s), cargo(es) or cargo source(s) gained their sender", named);
        } catch (Exception e) {
            log.error("Intake late-attribution pass failed", e);
        }
        // Both switches for the rest, because this half needs both to exist: the queue is the
        // parser's, and the numbers it reconciles against are the lookup's. With the lookup off
        // there is nothing here that could have changed since the last tick.
        if (!lookups.isEnabled()) return;
        try {
            int converted = intake.reconcileIdentifiedHulls();
            if (converted > 0) {
                log.info("Intake: {} new-vessel item(s) were hulls already on file, and are now "
                        + "particulars reviews", converted);
            }
        } catch (Exception e) {
            // Nothing above this catches. A dead scheduled method would stop the reconciliation
            // for the life of the process, silently.
            log.error("Intake reconciliation pass failed", e);
        }
    }
}
