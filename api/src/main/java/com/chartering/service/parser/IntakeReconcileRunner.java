package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.config.VesselLookupProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    private final IntakeService intake;
    private final ParserProperties parser;
    private final VesselLookupProperties lookups;

    @Scheduled(fixedDelay = TICK_MS, initialDelay = 45_000)
    public void run() {
        // Both switches, because the pass needs both halves to exist: the queue is the
        // parser's, and the numbers it reconciles against are the lookup's. With either off
        // there is nothing here that could have changed since the last tick.
        if (!parser.isEnabled() || !lookups.isEnabled()) return;
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
