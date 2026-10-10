package com.chartering.service.ask;

import com.chartering.dto.AskIndexStatusResponse;
import com.chartering.service.parser.EmbeddingClient;
import com.chartering.service.parser.EmbeddingClient.EmbeddingUnavailableException;
import com.chartering.tenancy.TenantContext;
import com.chartering.tenancy.TenantDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the desk's own text indexed, so "Ask the desk" has something to search.
 *
 * <p><b>Found by ids, read by batch, and never by comparing text.</b> The first step of every pass
 * asks {@link SearchChunkStore#pendingIds} which sources need indexing, and that answer is ids only.
 * The text of a source is read only once it is on that list, in batches of {@link #BATCH}, and then
 * embedded and written. A pass over a desk with nothing new therefore moves a few ids and nothing
 * else - the database is billed for data sent out, and this runs every ten minutes for every desk.
 *
 * <p><b>Two ways in, one body of work.</b> {@link #start} runs a pass on the worker thread for the
 * "Build index" button; {@link #tick} starts one for each desk that has something pending, on the
 * same timer as everything else here. The timer runs only when the feature is on, and a desk whose
 * run is already going is left to finish.
 *
 * <p><b>Progress is per desk; the embedding server is one.</b> The worker is a single thread for the
 * installation, because the server is one CPU process and two runs would only split its time. A
 * second desk's run waits its turn. Each desk's counts and errors are its own.
 *
 * <p><b>A run stops at the first unreachable server and keeps what it wrote.</b> Each source is
 * committed as it lands, so the next run starts from the first source that is still pending. The
 * vectors of other models are deleted only after a run that reached the end, so a run that died
 * halfway never leaves retrieval with nothing to compare.
 *
 * <p><b>Pruning happens first.</b> A deleted cargo, or a message since moved to Junk, has its chunks
 * removed before anything is indexed, so a passage is never answered from a record that is gone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchIndexService {

    /** Nomic's prefix for the side that is searched. The question carries {@link AskService#QUERY_PREFIX}. */
    static final String DOCUMENT_PREFIX = "search_document: ";

    /**
     * Sources per batch: one read of their text, one embedding call, one commit per source.
     * Small enough that a failure costs a batch, large enough that the read is not a round trip per
     * message.
     */
    static final int BATCH = 32;

    /** Ten minutes. Mail arrives all day; a question a few minutes stale is not a reason to read more often. */
    static final long TICK_MS = 10 * 60_000L;

    private final AskFeature feature;
    private final EmbeddingClient client;
    private final SearchChunkStore store;
    private final SearchDocumentReader documents;
    private final TenantDirectory tenants;

    /** Per desk: whether a run is going, and how far. */
    private final Map<Long, Progress> progress = new ConcurrentHashMap<>();

    /** One thread for the installation. See the class comment. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ask-index");
        t.setDaemon(true);
        return t;
    });

    /** Mutable run state for one desk. Written by the worker, read by status polls. */
    private static final class Progress {
        final AtomicBoolean running = new AtomicBoolean(false);
        volatile int done;
        volatile int total;
        volatile String lastError;
        volatile OffsetDateTime lastFinishedAt;
    }

    // ------------------------------------------------------------------ status

    /**
     * How far this desk's index has got, and how much is waiting. The pending count is the same id
     * query a run starts with, so it is exact and costs no text transfer.
     */
    public AskIndexStatusResponse status() {
        feature.requireEnabled();
        String model = client.model();
        Progress p = progressFor(TenantContext.require());
        long pending = 0;
        for (List<Long> ids : pendingAll(model).values()) {
            pending += ids.size();
        }
        return new AskIndexStatusResponse(
                model,
                store.indexedSources(model),
                pending,
                p.running.get(),
                p.done,
                p.total,
                p.lastError,
                p.lastFinishedAt);
    }

    // ------------------------------------------------------------------ runs

    /**
     * Starts an index run for this desk on the worker thread, and returns at once.
     *
     * @return false when this desk already has a run going
     */
    public boolean start() {
        feature.requireEnabled();
        Progress p = progressFor(TenantContext.require());
        if (!p.running.compareAndSet(false, true)) return false;
        try {
            // carry(): the worker has no request, and the run's writes must name the desk they are for.
            worker.submit(TenantContext.carry(() -> execute(p)));
        } catch (RuntimeException e) {
            p.running.set(false);
            throw e;
        }
        return true;
    }

    /** The same run as {@link #start}, on the calling thread, for tests. */
    void runNow() {
        Progress p = progressFor(TenantContext.require());
        if (!p.running.compareAndSet(false, true)) {
            throw new IllegalStateException("An index run is already going for this desk.");
        }
        execute(p);
    }

    /**
     * The timed pass: each active desk with something pending gets a run, and the rest are left alone.
     *
     * <p>Cheap when there is nothing to do: pruning is local to the database, and the check for
     * pending work is an id query per kind that stops at the first id found. Off when the feature is
     * off, so a deployment without the model does none of it.
     */
    @Scheduled(fixedDelay = TICK_MS, initialDelay = TICK_MS)
    public void tick() {
        if (!feature.isEnabled()) return;
        tenants.forEachActive("Ask the desk indexing", tenant -> {
            Progress p = progressFor(tenant);
            if (p.running.get()) return;
            store.pruneGone();
            if (hasPending(client.model())) start();
        });
    }

    /** One run, for the calling thread. Never throws: a failure is recorded as lastError. */
    private void execute(Progress p) {
        try {
            p.lastError = null;
            p.done = 0;
            p.total = 0;
            index(p);
        } catch (RuntimeException e) {
            log.warn("Ask the desk indexing run failed", e);
            p.lastError = e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            p.lastFinishedAt = OffsetDateTime.now();
            p.running.set(false);
        }
    }

    private void index(Progress p) {
        String model = client.model();
        store.pruneGone();
        Map<SourceKind, List<Long>> pending = pendingAll(model);
        int total = 0;
        for (List<Long> ids : pending.values()) total += ids.size();
        p.total = total;
        log.info("Indexing {} source(s) for search with {}", total, model);

        for (SourceKind kind : SourceKind.values()) {
            List<Long> ids = pending.get(kind);
            for (int from = 0; from < ids.size(); from += BATCH) {
                List<Long> batch = ids.subList(from, Math.min(from + BATCH, ids.size()));
                if (!indexBatch(p, kind, model, batch)) return;
            }
        }

        // Reached the end with every batch answered: the new model's index is complete, so the old
        // model's vectors can go. See the class comment for why this waits for the end.
        int removed = store.deleteOtherModels(model);
        if (removed > 0) log.info("Removed {} search chunk(s) from earlier models", removed);
    }

    /**
     * Embeds and writes one batch of sources. Returns false when the embedding server did not answer,
     * which ends the run; what was written before it stays.
     */
    private boolean indexBatch(Progress p, SourceKind kind, String model, List<Long> batch) {
        List<SearchDocument> docs = documents.load(kind, batch);
        List<List<String>> perDoc = new ArrayList<>(docs.size());
        List<String> texts = new ArrayList<>();
        for (SearchDocument doc : docs) {
            List<String> chunks = doc.chunks();
            perDoc.add(chunks);
            for (String chunk : chunks) texts.add(DOCUMENT_PREFIX + chunk);
        }
        if (texts.isEmpty()) {
            p.done += batch.size();
            return true;
        }

        List<float[]> vectors;
        try {
            vectors = client.embedAll(texts);
        } catch (EmbeddingUnavailableException e) {
            // Every later batch would spend its own timeout learning the same thing.
            p.lastError = e.getMessage();
            log.warn("Ask the desk indexing stopped after {} of {}: {}", p.done, p.total, e.getMessage());
            return false;
        }

        int v = 0;
        for (int d = 0; d < docs.size(); d++) {
            SearchDocument doc = docs.get(d);
            List<String> chunks = perDoc.get(d);
            List<SearchChunkStore.Chunk> rows = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                rows.add(new SearchChunkStore.Chunk(i, doc.ownerUserId(), doc.date(), doc.updatedAt(),
                        doc.title(), chunks.get(i), vectors.get(v++)));
            }
            store.replaceChunks(kind, doc.sourceId(), model, rows);
            p.done++;
        }
        // Sources in the batch that were deleted since the list was read are simply not in docs.
        return true;
    }

    // --------------------------------------------------------------- internals

    /** Pending source ids per kind, for this desk. Ids only. */
    private Map<SourceKind, List<Long>> pendingAll(String model) {
        Map<SourceKind, List<Long>> out = new EnumMap<>(SourceKind.class);
        for (SourceKind kind : SourceKind.values()) {
            out.put(kind, store.pendingIds(kind, model, Integer.MAX_VALUE));
        }
        return out;
    }

    private boolean hasPending(String model) {
        for (SourceKind kind : SourceKind.values()) {
            if (!store.pendingIds(kind, model, 1).isEmpty()) return true;
        }
        return false;
    }

    private Progress progressFor(Long tenantId) {
        return progress.computeIfAbsent(tenantId, k -> new Progress());
    }
}
