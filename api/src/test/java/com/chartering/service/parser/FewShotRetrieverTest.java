package com.chartering.service.parser;

import com.chartering.model.AnalysisSample;
import com.chartering.repository.AnalysisSampleRepository;
import com.chartering.service.SampleEmbeddingStore;
import com.chartering.service.TrainingTurns;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which examples the parser is shown, and in what order.
 *
 * <p>The behaviour that matters is what the retriever refuses to do: truncate an example, let a
 * dead embedding server fail a parse, or show the model its own answer. Each is a way to teach the
 * model something false while every other test still passes.
 */
@ExtendWith(MockitoExtension.class)
class FewShotRetrieverTest {

    private static final LocalDateTime SENT = LocalDateTime.of(2026, 9, 5, 9, 30);
    private static final String SUBJECT = "OPEN 07/10 SEPTEMBER";
    private static final String ANNOTATION = "{\"type\":\"vessel_opening\"}";

    @Mock
    private EmbeddingClient embeddings;

    @Mock
    private SampleEmbeddingStore store;

    @Mock
    private AnalysisSampleRepository samples;

    private final ObjectMapper json = new ObjectMapper();

    private FewShotRetriever retriever;

    private void setUpRetriever() {
        retriever = new FewShotRetriever(embeddings, store, samples, json);
    }

    private void enabledWith(float[] query) {
        when(embeddings.isEnabled()).thenReturn(true);
        when(embeddings.textFor(anyString(), any())).thenReturn("q");
        when(embeddings.embed("q")).thenReturn(query);
        when(embeddings.model()).thenReturn("m");
        setUpRetriever();
    }

    private static AnalysisSample sample(long id, String body) {
        AnalysisSample s = new AnalysisSample();
        s.setId(id);
        s.setSubject(SUBJECT);
        s.setSentAt(SENT);
        s.setBodyText(body);
        s.setAnnotation(ANNOTATION);
        return s;
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    private void neighbours(SampleEmbeddingStore.Neighbour... nearest) {
        when(store.nearest(any(), eq("m"), anyInt(), any())).thenReturn(List.of(nearest));
    }

    private void corpus(AnalysisSample... all) {
        when(samples.findAllById(any())).thenReturn(List.of(all));
    }

    @Test
    void disabledRetrievalAnswersNothingAndNeverAsksTheStore() {
        when(embeddings.isEnabled()).thenReturn(false);
        setUpRetriever();

        List<FewShotRetriever.Example> out = retriever.examplesFor(SUBJECT, SENT, "body", 3, 10_000,
                List.of(), 0);

        assertThat(out).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void zeroExamplesAnswersNothingWithoutEmbedding() {
        setUpRetriever();

        assertThat(retriever.examplesFor(SUBJECT, SENT, "body", 0, 10_000, List.of(), 0)).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void anUnavailableEmbeddingServerMeansZeroShotNotAFailedParse() {
        when(embeddings.isEnabled()).thenReturn(true);
        when(embeddings.textFor(anyString(), any())).thenReturn("q");
        when(embeddings.embed("q")).thenThrow(
                new EmbeddingClient.EmbeddingUnavailableException("Embedding server is down"));
        setUpRetriever();

        List<FewShotRetriever.Example> out = retriever.examplesFor(SUBJECT, SENT, "body", 3, 10_000,
                List.of(), 0);

        assertThat(out).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void anExampleThatDoesNotFitIsSkippedNeverCut() {
        enabledWith(new float[]{1f});
        AnalysisSample big = sample(1, repeat('a', 500));
        AnalysisSample small = sample(2, "A short circular.");
        neighbours(new SampleEmbeddingStore.Neighbour(1, 0.1), new SampleEmbeddingStore.Neighbour(2, 0.2));
        corpus(big, small);

        List<FewShotRetriever.Example> out = retriever.examplesFor(SUBJECT, SENT, "body", 2, 300,
                List.of(), 0);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).sampleId()).isEqualTo(2L);
        assertThat(out.get(0).user()).isEqualTo(TrainingTurns.userTurn(small));
    }

    @Test
    void theTextOfAnExampleIsTheWholeEmailOrNothing() {
        enabledWith(new float[]{1f});
        AnalysisSample fits = sample(1, "A complete circular that fits the budget.");
        neighbours(new SampleEmbeddingStore.Neighbour(1, 0.1));
        corpus(fits);

        List<FewShotRetriever.Example> out = retriever.examplesFor(SUBJECT, SENT, "body", 1, 10_000,
                List.of(), 0);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).user()).endsWith(fits.getBodyText());
        assertThat(out.get(0).assistant()).isEqualTo(ANNOTATION);
    }

    @Test
    void nearDuplicatesBelowTheMinimumDistanceAreDropped() {
        enabledWith(new float[]{1f});
        AnalysisSample copy = sample(1, "The same circular, re-sent.");
        AnalysisSample other = sample(2, "A different circular.");
        AnalysisSample third = sample(3, "A third circular.");
        neighbours(new SampleEmbeddingStore.Neighbour(1, 0.01),
                new SampleEmbeddingStore.Neighbour(2, 0.3),
                new SampleEmbeddingStore.Neighbour(3, 0.4));
        corpus(copy, other, third);

        List<FewShotRetriever.Example> out = retriever.examplesFor(SUBJECT, SENT, "body", 2, 10_000,
                List.of(), 0.05);

        assertThat(out).extracting(FewShotRetriever.Example::sampleId).containsExactly(3L, 2L);
    }

    @Test
    void excludedSamplesAreKeptOutOfTheSearchItself() {
        enabledWith(new float[]{1f});
        neighbours();

        retriever.examplesFor(SUBJECT, SENT, "body", 2, 10_000, List.of(7L), 0);

        verify(store).nearest(any(), eq("m"), eq(2 * FewShotRetriever.OVERFETCH), eq(List.of(7L)));
    }

    @Test
    void theNearestExampleSitsLastBesideTheQuestion() {
        enabledWith(new float[]{1f});
        neighbours(new SampleEmbeddingStore.Neighbour(1, 0.1),
                new SampleEmbeddingStore.Neighbour(2, 0.2),
                new SampleEmbeddingStore.Neighbour(3, 0.3));
        corpus(sample(1, "one"), sample(2, "two"), sample(3, "three"));

        List<FewShotRetriever.Example> out = retriever.examplesFor(SUBJECT, SENT, "body", 3, 10_000,
                List.of(), 0);

        assertThat(out).extracting(FewShotRetriever.Example::sampleId).containsExactly(3L, 2L, 1L);
        assertThat(out.get(2).distance()).isEqualTo(0.1);
    }

    @Test
    void neverAsksForMoreThanTheSlotsItNeedBeyondTheOverfetch() {
        enabledWith(new float[]{1f});
        neighbours();

        retriever.examplesFor(SUBJECT, SENT, "body", 2, 10_000, List.of(), 0);

        verify(store).nearest(any(), eq("m"), eq(6), any());
    }

    @Test
    void theExamplesFitTheBudgetTogether() {
        enabledWith(new float[]{1f});
        AnalysisSample a = sample(1, repeat('a', 200));
        AnalysisSample b = sample(2, repeat('b', 200));
        neighbours(new SampleEmbeddingStore.Neighbour(1, 0.1), new SampleEmbeddingStore.Neighbour(2, 0.2));
        corpus(a, b);
        int oneExample = TrainingTurns.userTurn(a).length() + ANNOTATION.length();

        List<FewShotRetriever.Example> out = retriever.examplesFor(SUBJECT, SENT, "body", 2,
                oneExample + 10, List.of(), 0);

        assertThat(out).extracting(FewShotRetriever.Example::sampleId).containsExactly(1L);
    }
}
