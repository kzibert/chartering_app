package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.model.FeedSource;
import com.chartering.repository.FeedSourceRepository;
import com.chartering.tenancy.TenantContext;
import com.chartering.tenancy.TenantDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reading the sources on a timer, and on demand — the parser sweep's shape.
 *
 * <p>A one-minute ticker that decides for itself whether the configured interval has passed,
 * because the interval is a runtime setting and Spring binds {@code fixedDelay} at startup. One
 * worker and one flag for the timer and the buttons alike: two fetches at once would read the
 * same pages twice and race on the unique index that stops the second copy being stored.
 *
 * <p><b>Unlike the parser sweep, a failure does not stop the run.</b> That sweep has one server
 * behind every message, so the first timeout speaks for the rest. Here every source is somebody
 * else's server: t.me being slow says nothing about ship.gr.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedFetchService {

    private static final long TICK_MS = 60_000;

    private final FeedProperties props;
    private final com.chartering.config.ParserProperties parser;
    private final FeedSettings settings;
    private final FeedSourceRepository sources;
    private final FeedFetchRunner runner;
    private final TenantDirectory tenants;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Seeded at boot, so a restart is not a reason to read every source at once. */
    private volatile OffsetDateTime lastFetch = OffsetDateTime.now();

    private volatile FetchReport lastReport;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feed-fetch");
        t.setDaemon(true);
        return t;
    });

    public record FetchReport(int sources, int newItems, int failed, String message,
                              OffsetDateTime finishedAt) {
    }

    @Scheduled(fixedDelay = TICK_MS, initialDelay = TICK_MS)
    public void tick() {
        if (!fetches()) return;
        int minutes = settings.fetchIntervalMinutes();
        if (minutes <= 0) return;
        if (Duration.between(lastFetch, OffsetDateTime.now()).toMinutes() < minutes) return;
        submit(null);
    }

    /**
     * Whether this deployment reads other people's pages at all.
     *
     * <p><b>Two features want the same fetch, and either of them is reason enough.</b>
     * {@code FEED_ANALYSIS_ENABLED} is about summarising — the model is in the office, and two
     * instances summarising the same sources would do the work twice. The Intake tab wants the
     * same pages for a different purpose, and it has its own switch already: with
     * {@code PARSER_ENABLED} on and a source marked to be read in, the boards have to be
     * fetched or the queue is permanently empty and nothing on screen says why.
     *
     * <p>It is still one fetch and one stored copy of each post. What this does not do is make
     * the hosted instance start reading pages: both switches are false there, and the condition
     * is an or of two falses.
     */
    private boolean fetches() {
        if (props.isAnalysisEnabled()) return true;
        // Any desk reading any enabled board into Intake. Asked desk by desk: the timer has no
        // desk of its own, and the subscriptions are each desk's.
        return parser.isEnabled() && tenants.activeIds().stream()
                .anyMatch(desk -> TenantContext.callAs(desk, sources::existsEnabledReadIntoIntake));
    }

    /** Fetch every enabled source now. */
    public void requestFetch() {
        requireEnabled();
        submit(null);
    }

    /** Fetch one source now, enabled or not — trying a source before switching it on is the point. */
    public void requestFetch(Long sourceId) {
        requireEnabled();
        submit(sourceId);
    }

    /**
     * Fetch the boards the Intake tab reads, from the Intake tab.
     *
     * <p>Behind {@code PARSER_ENABLED} rather than {@code FEED_ANALYSIS_ENABLED}, because that
     * is the switch the button is under: a deployment that reads circulars off the web but does
     * not summarise anything is an ordinary shape, and the Fetch button on a tab that exists
     * should not refuse on the strength of a feature the user is not looking at.
     *
     * @param sourceId one board, or null for every enabled one marked to be read in
     */
    public void requestIntakeFetch(Long sourceId) {
        if (!parser.isEnabled()) {
            throw new FeatureDisabledException(
                    "The email parser is not enabled on this deployment (PARSER_ENABLED).");
        }
        // Which boards "every board marked" means is the caller's desk's answer, so it is
        // settled here, on the request, rather than on the worker that has no desk.
        List<Long> boards = sourceId != null ? List.of(sourceId)
                : sources.findReadIntoIntake().stream().filter(FeedSource::isEnabled).map(FeedSource::getId).toList();
        if (running.get()) {
            log.debug("A feed fetch is already running; this request is skipped");
            return;
        }
        worker.submit(() -> fetchIfIdle(boards));
    }

    private void submit(Long onlySource) {
        if (running.get()) {
            log.debug("A feed fetch is already running; this request is skipped");
            return;
        }
        worker.submit(() -> fetchIfIdle(onlySource == null ? null : List.of(onlySource)));
    }

    public boolean isRunning() {
        return running.get();
    }

    public OffsetDateTime lastFetchAt() {
        return lastFetch;
    }

    public FetchReport lastReport() {
        return lastReport;
    }

    public OffsetDateTime nextFetchAt() {
        int minutes = settings.fetchIntervalMinutes();
        if (!fetches() || minutes <= 0) return null;
        return lastFetch.plusMinutes(minutes);
    }

    /** @param only the sources to read, or null for every enabled one (the timer's pass) */
    private void fetchIfIdle(List<Long> only) {
        if (!running.compareAndSet(false, true)) return;
        try {
            lastReport = fetch(only);
        } catch (Exception e) {
            log.error("Feed fetch failed", e);
            lastReport = new FetchReport(0, 0, 0, "The fetch failed: " + e.getMessage(), OffsetDateTime.now());
        } finally {
            if (only == null) lastFetch = OffsetDateTime.now();
            running.set(false);
        }
    }

    private FetchReport fetch(List<Long> only) {
        List<Long> ids = only != null ? only
                : sources.findByEnabledTrueOrderByIdAsc().stream().map(FeedSource::getId).toList();
        if (ids.isEmpty()) {
            return new FetchReport(0, 0, 0, only != null
                    ? "No sources are marked to be read into Intake."
                    : "No enabled sources to read.", OffsetDateTime.now());
        }
        int added = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                added += runner.store(id, runner.read(id));
            } catch (Exception e) {
                failed++;
                String message = e instanceof FeedReader.FeedFetchException ? e.getMessage() : e.toString();
                log.warn("Feed source {} could not be read: {}", id, message);
                try {
                    runner.recordFailure(id, message);
                } catch (Exception recording) {
                    log.error("Could not record the failure of feed source {}", id, recording);
                }
            }
        }
        String message = "Read %d source(s): %d new item(s)%s.".formatted(ids.size(), added,
                failed > 0 ? ", " + failed + " failed" : "");
        log.info("Feed fetch: {}", message);
        return new FetchReport(ids.size(), added, failed, message, OffsetDateTime.now());
    }

    private void requireEnabled() {
        if (!props.isAnalysisEnabled()) {
            throw new FeatureDisabledException(
                    "Fetching feed sources is not enabled on this deployment (FEED_ANALYSIS_ENABLED).");
        }
    }
}
