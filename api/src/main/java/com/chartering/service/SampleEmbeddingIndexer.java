package com.chartering.service;

import com.chartering.config.AnalysisProperties;
import com.chartering.dto.AnalysisEmbeddingStatusResponse;
import com.chartering.exception.EmbeddingNotConfiguredException;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.model.AnalysisStatus;
import com.chartering.repository.AnalysisSampleRepository;
import com.chartering.repository.AnalysisSampleText;
import com.chartering.service.parser.EmbeddingClient;
import com.chartering.service.parser.EmbeddingClient.EmbeddingUnavailableException;
import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps one vector per READY corpus sample, so the parser can be shown the labelled examples most
 * like the email it is reading.
 *
 * <p><b>Only READY samples are embedded.</b> A sample is READY once a person has said its
 * annotation is fit to train on; an unreviewed one has no answer to show a model, so it is not a
 * fit example to retrieve. {@link SampleEmbeddingStore#nearest} filters on READY as well, so a
 * sample that leaves READY stops being offered even though its vector is still on file.
 *
 * <p><b>Two ways in, one body of work.</b> A sample that becomes READY is embedded on the spot by
 * {@link #indexOne}, so a fresh example is retrievable straight away. Everything else - the first
 * index of a corpus, a change of model or prefix, a run after the server was down - goes through
 * {@link #start}, which embeds whatever is stale in batches on a worker thread.
 *
 * <p><b>Progress is per desk.</b> The embedding server is one process for the installation, but
 * which samples are stale and how far a run has got belong to the desk asking, the same as the
 * feed summary's progress. A run is refused for a desk that already has one going; another desk's
 * run reads as idle here.
 *
 * <p><b>A run stops at the first unreachable server, and keeps what it wrote.</b> Each batch is
 * committed as it lands, so a server that dies at the fourth batch leaves three batches' vectors
 * in place and the next run starts from the fourth. Deleting the other models' vectors happens
 * only after a run that reached the end: until then the old generation is still on file.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SampleEmbeddingIndexer {

    /**
     * Samples per request. Matches the client's own batch size, so one batch here is one request
     * there and progress moves once per round trip.
     */
    private static final int BATCH = 16;

    private final AnalysisProperties props;
    private final EmbeddingClient client;
    private final SampleEmbeddingStore store;
    private final AnalysisSampleRepository samples;

    /** Per desk: whether a run is going, and how far. Created on first use by that desk. */
    private final Map<Long, Progress> progress = new ConcurrentHashMap<>();

    /**
     * One thread for the installation. The embedding server is one CPU process, and two runs
     * sharing it would only split the same time between them; a second desk's run waits its turn.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sample-embedding");
        t.setDaemon(true);
        return t;
    });

    /** Mutable run state for one desk. Written by the worker, read by status polls. */
    private static final class Progress {
        final AtomicBoolean running = new AtomicBoolean(false);
        volatile int done;
        volatile int total;
        volatile String lastError;
        volatile LocalDateTime lastFinishedAt;
    }

    /** A sample whose vector is missing or stale: its id, the text to embed, and that text's hash. */
    private record Pending(long id, String text, String hash) {
    }

    // ------------------------------------------------------------------ status

    /**
     * How far this desk's index has got. Counts are taken from the current text of every READY
     * sample, so a stale count is exact rather than a record of what the last run believed.
     */
    @Transactional(readOnly = true)
    public AnalysisEmbeddingStatusResponse status() {
        requireEnabled();
        Progress p = progressFor(TenantContext.require());
        long ready = samples.countByStatus(AnalysisStatus.READY);
        long stale = stale(client.model()).size();
        return new AnalysisEmbeddingStatusResponse(
                client.isEnabled(),
                client.model(),
                ready,
                ready - stale,
                stale,
                p.running.get(),
                p.done,
                p.total,
                p.lastError,
                p.lastFinishedAt);
    }

    /**
     * Refuses with 404 when the analysis feature is off, and with 503 when the feature is on but
     * EMBEDDING_URL is blank. The endpoint that starts a run asks this first, so the two answers
     * are not confused with the 409 for a run already going.
     */
    public void requireConfigured() {
        requireEnabled();
        if (!client.isEnabled()) {
            throw new EmbeddingNotConfiguredException("Set EMBEDDING_URL to the embeddings endpoint "
                    + "(for example http://host:8092/v1/embeddings) to build the retrieval index. "
                    + "The sibling chartering-ml project serves it with make serve-embed.");
        }
    }

    // ------------------------------------------------------------------ runs

    /**
     * Starts an index run for this desk on the worker thread. Returns at once.
     *
     * @return false if this desk already has a run going, or the embedding server is not
     *     configured; the controller asks {@link #requireConfigured()} first, so in practice false
     *     means "already running".
     */
    public boolean start() {
        requireEnabled();
        if (!client.isEnabled()) return false;
        Progress p = progressFor(TenantContext.require());
        if (!p.running.compareAndSet(false, true)) return false;
        try {
            // carry() brings the desk and the login to the worker: the thread has no request, and
            // the run's writes must name the desk they are for.
            worker.submit(TenantContext.carry(() -> execute(p)));
        } catch (RuntimeException e) {
            p.running.set(false);
            throw e;
        }
        return true;
    }

    /**
     * The same run as {@link #start}, on the calling thread, for tests. Refuses a second run at
     * once rather than waiting for the first.
     */
    void runNow() {
        Progress p = progressFor(TenantContext.require());
        if (!p.running.compareAndSet(false, true)) {
            throw new IllegalStateException("An index run is already going for this desk.");
        }
        execute(p);
    }

    /**
     * Embeds one sample now, if it is READY and its vector is missing or stale. Called after the
     * save that made it READY has committed, so a slow or absent embedding server never holds
     * that transaction open - see {@code AnalysisService}.
     *
     * <p>Best effort by design: a failure is one warning line, and the sample is picked up by the
     * next index run. The save it follows has already happened and must not be reported as failed
     * because a vector could not be computed.
     */
    public void indexOne(long sampleId) {
        if (!client.isEnabled()) return;
        try {
            Optional<AnalysisSampleText> found = samples.findTextByIdAndStatus(sampleId, AnalysisStatus.READY);
            if (found.isEmpty()) return;
            AnalysisSampleText s = found.get();
            String model = client.model();
            String text = client.textFor(s.subject(), s.bodyText());
            String hash = EmbeddingClient.hash(text);
            if (hash.equals(store.hashesFor(model).get(sampleId))) return;
            store.upsert(sampleId, model, hash, client.embed(text));
        } catch (RuntimeException e) {
            log.warn("Sample {} was not embedded now; the next index run will embed it: {}",
                    sampleId, e.getMessage());
        }
    }

    /** The whole run, for the calling thread. Never throws: a failure is recorded as lastError. */
    private void execute(Progress p) {
        try {
            p.lastError = null;
            p.done = 0;
            p.total = 0;
            index(p);
        } catch (RuntimeException e) {
            log.warn("Sample embedding run failed", e);
            p.lastError = e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            p.lastFinishedAt = LocalDateTime.now();
            p.running.set(false);
        }
    }

    private void index(Progress p) {
        String model = client.model();
        List<Pending> todo = stale(model);
        p.total = todo.size();
        log.info("Indexing {} sample(s) with {}", todo.size(), model);

        for (int from = 0; from < todo.size(); from += BATCH) {
            List<Pending> batch = todo.subList(from, Math.min(from + BATCH, todo.size()));
            List<float[]> vectors;
            try {
                vectors = client.embedAll(batch.stream().map(Pending::text).toList());
            } catch (EmbeddingUnavailableException e) {
                // Every later batch would spend its own timeout learning the same thing. What is
                // already written stays; the old model's vectors stay too, since this run did not
                // reach the end that makes the new generation the only one.
                p.lastError = e.getMessage();
                log.warn("Sample embedding stopped after {} of {}: {}", p.done, p.total, e.getMessage());
                return;
            }
            for (int i = 0; i < batch.size(); i++) {
                write(batch.get(i), model, vectors.get(i));
                p.done++;
            }
        }

        // Reached the end with every batch answered: the new generation is complete, so the old
        // one can go. Skipped samples do not count against this - a sample deleted mid-run has
        // nothing to keep a vector for.
        int removed = store.deleteOtherModels(model);
        if (removed > 0) log.info("Removed {} vector(s) from earlier models", removed);
    }

    /**
     * One vector written. A sample deleted since the list was read is skipped rather than failing
     * the run: the store refuses a sample that is not on this desk, and a foreign key refuses one
     * that is gone.
     */
    private void write(Pending s, String model, float[] vector) {
        try {
            store.upsert(s.id(), model, s.hash(), vector);
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            log.warn("Sample {} skipped: {}", s.id(), e.getMessage());
        }
    }

    /**
     * READY samples whose vector is missing, or was computed from text that has since changed.
     * The hash covers the prefix and the truncation too, so a change to either shows up here
     * without anybody having to name it.
     */
    private List<Pending> stale(String model) {
        Map<Long, String> have = store.hashesFor(model);
        List<Pending> stale = new ArrayList<>();
        for (AnalysisSampleText s : samples.findTextByStatus(AnalysisStatus.READY)) {
            String text = client.textFor(s.subject(), s.bodyText());
            String hash = EmbeddingClient.hash(text);
            if (!hash.equals(have.get(s.id()))) {
                stale.add(new Pending(s.id(), text, hash));
            }
        }
        return stale;
    }

    // --------------------------------------------------------------- internals

    private Progress progressFor(Long tenantId) {
        return progress.computeIfAbsent(tenantId, k -> new Progress());
    }

    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new FeatureDisabledException(
                    "Email analysis is not enabled on this deployment (ANALYSIS_ENABLED).");
        }
    }
}
