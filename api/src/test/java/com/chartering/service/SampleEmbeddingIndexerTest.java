package com.chartering.service;

import com.chartering.config.AnalysisProperties;
import com.chartering.dto.AnalysisEmbeddingStatusResponse;
import com.chartering.model.AnalysisStatus;
import com.chartering.repository.AnalysisSampleRepository;
import com.chartering.repository.AnalysisSampleText;
import com.chartering.service.parser.EmbeddingClient;
import com.chartering.service.parser.EmbeddingClient.EmbeddingUnavailableException;
import com.chartering.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The index run with a fake embedding client and a mocked store: what counts as stale, how a run
 * stops at an unreachable server, when the old model's vectors are removed, and that a sample
 * deleted mid-run does not fail the run. No Spring context, no database.
 */
class SampleEmbeddingIndexerTest {

    private static final long DESK = 7L;
    private static final String MODEL = "test-model";

    private AnalysisProperties props;
    private EmbeddingClient client;
    private SampleEmbeddingStore store;
    private AnalysisSampleRepository samples;
    private SampleEmbeddingIndexer indexer;

    @BeforeEach
    void setUp() {
        props = new AnalysisProperties();
        props.setEnabled(true);
        client = mock(EmbeddingClient.class);
        store = mock(SampleEmbeddingStore.class);
        samples = mock(AnalysisSampleRepository.class);

        when(client.isEnabled()).thenReturn(true);
        when(client.model()).thenReturn(MODEL);
        when(client.textFor(any(), anyString())).thenAnswer(inv ->
                "p:" + inv.getArgument(1));
        when(client.embed(anyString())).thenReturn(new float[]{1f});
        // One component per input: its length, so a vector can be traced back to its text.
        when(client.embedAll(anyList())).thenAnswer(inv -> vectorsFor(inv.getArgument(0)));
        when(store.hashesFor(MODEL)).thenReturn(Map.of());

        indexer = new SampleEmbeddingIndexer(props, client, store, samples);
    }

    private static List<float[]> vectorsFor(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (String t : texts) out.add(new float[]{t.length()});
        return out;
    }

    private void ready(AnalysisSampleText... rows) {
        when(samples.countByStatus(AnalysisStatus.READY)).thenReturn((long) rows.length);
        when(samples.findTextByStatus(AnalysisStatus.READY)).thenReturn(List.of(rows));
    }

    private static AnalysisSampleText row(long id, String body) {
        return new AnalysisSampleText(id, "subject " + id, body);
    }

    private static String hashOf(AnalysisSampleText s) {
        return EmbeddingClient.hash("p:" + s.bodyText());
    }

    private AnalysisEmbeddingStatusResponse status() {
        return TenantContext.callAs(DESK, () -> indexer.status());
    }

    private void run() {
        TenantContext.runAs(DESK, () -> indexer.runNow());
    }

    @Test
    void staleIsMissingOrChangedAndNothingElse() {
        AnalysisSampleText current = row(1, "one");
        AnalysisSampleText changed = row(2, "two");
        AnalysisSampleText missing = row(3, "three");
        ready(current, changed, missing);
        Map<Long, String> have = new HashMap<>();
        have.put(1L, hashOf(current));
        have.put(2L, "hash-from-an-older-text");
        when(store.hashesFor(MODEL)).thenReturn(have);

        AnalysisEmbeddingStatusResponse s = status();

        assertThat(s.ready()).isEqualTo(3);
        assertThat(s.stale()).isEqualTo(2);
        assertThat(s.indexed()).isEqualTo(1);
        assertThat(s.running()).isFalse();
        assertThat(s.lastError()).isNull();
    }

    @Test
    void aRunEmbedsOnlyTheStaleSamplesAndThenRemovesOtherModels() {
        AnalysisSampleText current = row(1, "one");
        AnalysisSampleText a = row(2, "two");
        AnalysisSampleText b = row(3, "three");
        ready(current, a, b);
        when(store.hashesFor(MODEL)).thenReturn(Map.of(1L, hashOf(current)));
        when(store.deleteOtherModels(MODEL)).thenReturn(4);

        run();

        verify(store).upsert(2L, MODEL, hashOf(a), new float[]{"p:two".length()});
        verify(store).upsert(3L, MODEL, hashOf(b), new float[]{"p:three".length()});
        verify(store, never()).upsert(eq(1L), anyString(), anyString(), any());
        verify(store).deleteOtherModels(MODEL);
        AnalysisEmbeddingStatusResponse s = status();
        assertThat(s.running()).isFalse();
        assertThat(s.done()).isEqualTo(2);
        assertThat(s.total()).isEqualTo(2);
        assertThat(s.lastFinishedAt()).isNotNull();
    }

    @Test
    void theRunSendsSixteenAtATime() {
        List<AnalysisSampleText> many = new ArrayList<>();
        for (long i = 1; i <= 20; i++) many.add(row(i, "body " + i));
        ready(many.toArray(new AnalysisSampleText[0]));

        run();

        verify(client, org.mockito.Mockito.times(2)).embedAll(anyList());
        verify(store, org.mockito.Mockito.times(20)).upsert(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void anUnreachableServerStopsTheRunAndKeepsWhatWasWritten() {
        List<AnalysisSampleText> many = new ArrayList<>();
        for (long i = 1; i <= 20; i++) many.add(row(i, "body " + i));
        ready(many.toArray(new AnalysisSampleText[0]));

        AtomicInteger calls = new AtomicInteger();
        when(client.embedAll(anyList())).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) return vectorsFor(inv.getArgument(0));
            throw new EmbeddingUnavailableException(
                    "Embedding server at http://x/v1/embeddings could not get vectors: refused");
        });

        run();

        // The first batch of sixteen is kept; the second batch is never written.
        verify(store, org.mockito.Mockito.times(16)).upsert(anyLong(), anyString(), anyString(), any());
        // The old generation is not removed: this run did not reach the end.
        verify(store, never()).deleteOtherModels(anyString());
        AnalysisEmbeddingStatusResponse s = status();
        assertThat(s.lastError()).contains("could not get vectors");
        assertThat(s.done()).isEqualTo(16);
        assertThat(s.total()).isEqualTo(20);
        assertThat(s.running()).isFalse();
    }

    @Test
    void aSampleDeletedMidRunIsSkippedAndTheRunStillCompletes() {
        AnalysisSampleText gone = row(2, "two");
        ready(row(1, "one"), gone, row(3, "three"));
        doThrow(new IllegalArgumentException("analysis sample 2 is not on this desk"))
                .when(store).upsert(eq(2L), anyString(), anyString(), any());

        run();

        verify(store).upsert(eq(1L), anyString(), anyString(), any());
        verify(store).upsert(eq(3L), anyString(), anyString(), any());
        verify(store).deleteOtherModels(MODEL);
        assertThat(status().lastError()).isNull();
    }

    @Test
    void startIsRefusedWhenTheServerIsNotConfigured() {
        when(client.isEnabled()).thenReturn(false);

        boolean started = TenantContext.callAs(DESK, () -> indexer.start());

        assertThat(started).isFalse();
        assertThat(status().enabled()).isFalse();
    }

    @Test
    void indexOneEmbedsAReadySampleAndLeavesACurrentOneAlone() {
        AnalysisSampleText s = row(5, "five");
        when(samples.findTextByIdAndStatus(5L, AnalysisStatus.READY)).thenReturn(Optional.of(s));

        TenantContext.runAs(DESK, () -> indexer.indexOne(5L));
        verify(store).upsert(5L, MODEL, hashOf(s), new float[]{1f});

        when(store.hashesFor(MODEL)).thenReturn(Map.of(5L, hashOf(s)));
        TenantContext.runAs(DESK, () -> indexer.indexOne(5L));
        // Still one call in total: the second one found the vector current and did nothing.
        verify(store, org.mockito.Mockito.times(1)).upsert(eq(5L), anyString(), anyString(), any());
    }

    @Test
    void indexOneSwallowsAnUnreachableServer() {
        AnalysisSampleText s = row(6, "six");
        when(samples.findTextByIdAndStatus(6L, AnalysisStatus.READY)).thenReturn(Optional.of(s));
        when(client.embed(anyString())).thenThrow(new EmbeddingUnavailableException("refused"));

        // The save that made the sample READY has already happened; this must not raise.
        TenantContext.runAs(DESK, () -> indexer.indexOne(6L));

        verify(store, never()).upsert(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void indexOneDoesNothingForASampleThatIsNotReady() {
        when(samples.findTextByIdAndStatus(9L, AnalysisStatus.READY)).thenReturn(Optional.empty());

        TenantContext.runAs(DESK, () -> indexer.indexOne(9L));

        verify(store, never()).upsert(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void theFeatureBeingOffRefusesEverything() {
        props.setEnabled(false);

        assertThatThrownBy(this::status)
                .isInstanceOf(com.chartering.exception.FeatureDisabledException.class);
    }

}
