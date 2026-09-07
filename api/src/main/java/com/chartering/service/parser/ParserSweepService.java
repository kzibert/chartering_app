package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.model.MailMessage;
import com.chartering.repository.ParsedEmailRepository;
import com.chartering.service.ParserSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reading the mailbox on a timer, and on demand.
 *
 * <h2>Why a ticker rather than a fixed schedule</h2>
 * <p>How often this runs is a runtime setting on the Settings tab, not a property — it is a
 * knob turned while watching the queue, and an environment variable is a redeploy. Spring's
 * {@code fixedDelay} is bound at startup and cannot follow that, so the method below runs on
 * a short fixed beat and decides for itself whether the configured interval has elapsed. The
 * cost is a no-op wake-up a minute; the alternative is a scheduler rebuilt whenever somebody
 * changes a number.
 *
 * <p>The first sweep is one interval after boot rather than immediately, matching the mailbox
 * poller: a restart is not a reason to spend GPU time, and a container that crash-loops would
 * otherwise re-read the backlog on every attempt.
 *
 * <h2>Why parsing is not part of the mail sync</h2>
 * <p>The sync is a network read against an IMAP server that must finish; parsing is minutes of
 * somebody else's GPU. Chaining them would mean a mailbox that stops updating because a
 * workstation is asleep, and it would put the sync's transaction around forty model calls.
 * They share nothing but a table, which is the right amount.
 *
 * <h2>One at a time, whoever asked</h2>
 * <p>The timer and the Parse now button share one flag and one worker thread, for the reason
 * the mailbox sync does: two sweeps would take the same unparsed rows, spend the model's time
 * twice on them, and race on the unique index that prevents the second from being stored.
 * The button therefore returns immediately and the tab watches {@link #isRunning()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParserSweepService {

    /**
     * How often the ticker looks at the clock. Not how often a sweep runs — that is the
     * user's interval, and this only has to be fine enough that "every 30 minutes" is not
     * quietly every 45.
     */
    private static final long TICK_MS = 60_000;

    private final ParserProperties props;
    private final ParserSettings settings;
    private final ParsedEmailRepository parsedEmails;
    private final EmailParseRunner runner;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Seeded at boot so the first sweep is an interval away rather than immediate. In memory
     * rather than in the database on purpose: it is "when did this process last look", and a
     * restart genuinely should start the clock again — persisting it would mean a container
     * that came up after a long weekend immediately spending an hour of GPU on a backlog
     * nobody asked for.
     */
    private volatile OffsetDateTime lastSweep = OffsetDateTime.now();

    private volatile SweepReport lastReport;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "email-parser-sweep");
        t.setDaemon(true);
        return t;
    });

    /**
     * What a sweep did.
     *
     * @param unreachable true when the model server did not answer at all — the difference
     *                    between "nothing to read" and "could not read", which are the two
     *                    outcomes a user most needs told apart
     */
    public record SweepReport(int read, int failed, int skipped, int positions, int cargoes,
                              int items, boolean unreachable, String message,
                              OffsetDateTime finishedAt) {
    }

    // ---------------------------------------------------------------- entry points

    /** The timer. Quiet when the feature is off, and quiet when the interval is set to 0. */
    @Scheduled(fixedDelay = TICK_MS, initialDelay = TICK_MS)
    public void tick() {
        if (!props.isEnabled()) return;
        int minutes = settings.sweepIntervalMinutes();
        if (minutes <= 0) return;
        if (Duration.between(lastSweep, OffsetDateTime.now()).toMinutes() < minutes) return;
        submit();
    }

    /**
     * The Parse now button.
     *
     * <p>Works whatever the interval is set to, including 0 — "off" means the timer does not
     * run it, not that it cannot be run. Refused only when the feature itself is off, which
     * is a 404 rather than a 400: the endpoint is not part of that deployment.
     */
    public void requestSweep() {
        requireEnabled();
        submit();
    }

    private void submit() {
        // Checked before submitting as well as inside, so a click during a running sweep is
        // not queued behind it - the queue is the unparsed rows, and by the time this one
        // finished the second run would have nothing to do anyway.
        if (running.get()) {
            log.debug("A parser sweep is already running; this request is skipped");
            return;
        }
        worker.submit(this::sweepIfIdle);
    }

    public boolean isRunning() {
        return running.get();
    }

    public OffsetDateTime lastSweepAt() {
        return lastSweep;
    }

    public SweepReport lastReport() {
        return lastReport;
    }

    /** When the timer will next look, or null when nothing is scheduled. */
    public OffsetDateTime nextSweepAt() {
        int minutes = settings.sweepIntervalMinutes();
        if (!props.isEnabled() || minutes <= 0) return null;
        return lastSweep.plusMinutes(minutes);
    }

    // ---------------------------------------------------------------- the sweep

    private void sweepIfIdle() {
        if (!running.compareAndSet(false, true)) return;
        try {
            lastReport = sweep();
        } catch (Exception e) {
            // Nothing above this catches. The worker thread dying would stop the mailbox
            // being read for the life of the process, silently.
            log.error("Parser sweep failed", e);
            lastReport = new SweepReport(0, 0, 0, 0, 0, 0, false,
                    "The sweep failed: " + e.getMessage(), OffsetDateTime.now());
        } finally {
            lastSweep = OffsetDateTime.now();
            running.set(false);
        }
    }

    private SweepReport sweep() {
        // Read once and used throughout: the batch size and the lookback have to come from
        // the same snapshot, or a save landing mid-sweep would page one against the other.
        ParserSettings.Values values = settings.values();
        int batch = values.sweepBatchSize();
        List<MailMessage> queue = new ArrayList<>(
                parsedEmails.unparsed(values.receivedSince(), PageRequest.of(0, batch)));

        // Failures come after the fresh mail and only fill what is left of the batch. A
        // backlog of timeouts must not push today's circulars behind tomorrow.
        if (queue.size() < batch) {
            parsedEmails.retryable(props.getMaxAttempts(), PageRequest.of(0, batch - queue.size()))
                    .forEach(p -> queue.add(p.getMailMessage()));
        }

        if (queue.isEmpty()) {
            // Named rather than a bare "nothing": with a lookback set, "nothing new" and
            // "nothing new inside the window" are different situations and only one of them
            // is fixed by widening it.
            String nothing = values.sweepMaxAgeDays() > 0
                    ? "Nothing new to read in the last " + values.sweepMaxAgeDays() + " days."
                    : "Nothing new to read.";
            return new SweepReport(0, 0, 0, 0, 0, 0, false, nothing, OffsetDateTime.now());
        }

        int read = 0;
        int failed = 0;
        int skipped = 0;
        int positions = 0;
        int cargoes = 0;
        int items = 0;
        boolean unreachable = false;

        for (MailMessage message : queue) {
            try {
                IntakeService.ApplyOutcome outcome = runner.parseOne(message.getId());
                if (outcome == null) {
                    skipped++;
                } else {
                    read++;
                    positions += outcome.positionsApplied();
                    cargoes += outcome.cargoesApplied();
                    items += outcome.itemsRaised();
                }
            } catch (EmailParserClient.ParserUnavailableException e) {
                failed++;
                // The server being down is not forty separate failures, it is one. Stopping
                // saves forty timeouts - three minutes each - and leaves the rest of the
                // queue untouched for the next sweep, which is where they belong anyway.
                unreachable = true;
                log.warn("Stopping the sweep: {}", e.getMessage());
                break;
            } catch (Exception e) {
                failed++;
                log.warn("Message {} could not be read: {}", message.getId(), e.toString());
            }
        }

        String summary = unreachable
                ? "Stopped: the model server did not answer."
                : "Read %d, %d failed, %d skipped.".formatted(read, failed, skipped);
        log.info("Parser sweep: {} positions {} cargoes {} items {}",
                summary, positions, cargoes, items);
        return new SweepReport(read, failed, skipped, positions, cargoes, items, unreachable,
                summary, OffsetDateTime.now());
    }

    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new FeatureDisabledException(
                    "The email parser is not enabled on this deployment (PARSER_ENABLED).");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
