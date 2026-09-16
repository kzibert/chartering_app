package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.model.FeedSource;
import com.chartering.repository.FeedSourceRepository;
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
    private final FeedSettings settings;
    private final FeedSourceRepository sources;
    private final FeedFetchRunner runner;

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
        if (!props.isAnalysisEnabled()) return;
        int minutes = settings.fetchIntervalMinutes();
        if (minutes <= 0) return;
        if (Duration.between(lastFetch, OffsetDateTime.now()).toMinutes() < minutes) return;
        submit(null);
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

    private void submit(Long onlySource) {
        if (running.get()) {
            log.debug("A feed fetch is already running; this request is skipped");
            return;
        }
        worker.submit(() -> fetchIfIdle(onlySource));
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
        if (!props.isAnalysisEnabled() || minutes <= 0) return null;
        return lastFetch.plusMinutes(minutes);
    }

    private void fetchIfIdle(Long onlySource) {
        if (!running.compareAndSet(false, true)) return;
        try {
            lastReport = fetch(onlySource);
        } catch (Exception e) {
            log.error("Feed fetch failed", e);
            lastReport = new FetchReport(0, 0, 0, "The fetch failed: " + e.getMessage(), OffsetDateTime.now());
        } finally {
            if (onlySource == null) lastFetch = OffsetDateTime.now();
            running.set(false);
        }
    }

    private FetchReport fetch(Long onlySource) {
        List<Long> ids = onlySource != null ? List.of(onlySource)
                : sources.findByEnabledTrueOrderByIdAsc().stream().map(FeedSource::getId).toList();
        if (ids.isEmpty()) {
            return new FetchReport(0, 0, 0, "No enabled sources to read.", OffsetDateTime.now());
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
