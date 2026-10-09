package com.chartering.service.parser;

import com.chartering.model.AnalysisSample;
import com.chartering.repository.AnalysisSampleRepository;
import com.chartering.service.SampleEmbeddingStore;
import com.chartering.service.TrainingTurns;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the labelled corpus samples to show the parser before it reads an email.
 *
 * <p><b>An experiment, off by default.</b> The finetuned model was trained and measured with a
 * system prompt and one user turn. Putting worked examples in front of it changes what it is
 * being asked, so {@code parser.fewShotExamples} stays at zero until the chartering-ml harness has
 * scored the same retrieved examples against the same held-out emails. The few-shot endpoint
 * exists so that harness measures exactly what production would send, through this class.
 *
 * <p><b>Nearest last.</b> The example most like the question sits directly above it, where a model
 * attends most, and the least like it is furthest back.
 *
 * <p><b>The turns are the export's turns.</b> Each example is built by {@link TrainingTurns}, the
 * same code that writes the training file, so an example in a request looks exactly like an example
 * the model was finetuned on. A second copy of that layout here is the drift this class must not
 * create.
 *
 * <p><b>Never truncated.</b> An email cut short paired with its full annotation teaches the model
 * to report things that are not in the text. An example that does not fit the character budget is
 * skipped and the next nearest is tried, so the budget costs examples and never accuracy.
 *
 * <p><b>The parse goes on without examples.</b> A dead embedding server must not turn every
 * email into a failed parse; a zero-shot reading is what the model was measured under, so falling
 * back to it is the honest answer. One warning line says so, and the caller carries on.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FewShotRetriever {

    /**
     * How many neighbours are asked for per example slot.
     *
     * <p>The nearest few are often unusable: a near-duplicate that the evaluation drops, or a sample
     * too long for the budget. Asking for three times the slots leaves room to skip them without
     * settling for fewer examples than the setting asked for.
     */
    static final int OVERFETCH = 3;

    /**
     * One example as it will be sent: the sample it came from, how far it sits from the question,
     * and the two turns.
     */
    public record Example(long sampleId, double distance, String user, String assistant) {
    }

    private final EmbeddingClient embeddings;
    private final SampleEmbeddingStore store;
    private final AnalysisSampleRepository samples;
    private final ObjectMapper json;

    /**
     * Up to {@code k} examples for one email, nearest last, within {@code maxChars} of text.
     *
     * @param excludeSampleIds samples never to offer; the evaluation passes the sample it is
     *                         scoring so it cannot be shown its own answer
     * @param minDistance      neighbours closer than this are dropped. The evaluation uses it to keep
     *                         out a re-sent copy of the same circular: a near-duplicate is the answer
     *                         to the question, not an example of how to answer it. Zero keeps all.
     * @return the examples chosen, possibly fewer than {@code k}; empty when retrieval is off, the
     *         embedding server does not answer, or nothing fits
     */
    @Transactional(readOnly = true)
    public List<Example> examplesFor(String subject, LocalDateTime sentAt, String body, int k,
                                     int maxChars, Collection<Long> excludeSampleIds,
                                     double minDistance) {
        if (k <= 0 || !embeddings.isEnabled()) {
            return List.of();
        }

        float[] query;
        try {
            query = embeddings.embed(embeddings.textFor(subject, body));
        } catch (EmbeddingClient.EmbeddingUnavailableException e) {
            log.warn("Few-shot retrieval skipped, this email is parsed without examples: {}",
                    e.getMessage());
            return List.of();
        }

        List<SampleEmbeddingStore.Neighbour> near = store.nearest(query, embeddings.model(),
                k * OVERFETCH, excludeSampleIds);
        List<SampleEmbeddingStore.Neighbour> kept = near.stream()
                .filter(n -> n.distance() >= minDistance)
                .toList();
        if (kept.isEmpty()) {
            return List.of();
        }

        // One query for all of them. The tenant filter applies to this load as it does to every
        // other, so a neighbour from another desk could not be read even if the store returned it.
        List<Long> ids = kept.stream().map(SampleEmbeddingStore.Neighbour::sampleId).toList();
        Map<Long, AnalysisSample> byId = new HashMap<>();
        for (AnalysisSample s : samples.findAllById(ids)) {
            byId.put(s.getId(), s);
        }

        List<Example> chosen = new ArrayList<>(k);
        int remaining = maxChars;
        for (SampleEmbeddingStore.Neighbour n : kept) {
            if (chosen.size() == k) break;
            AnalysisSample s = byId.get(n.sampleId());
            if (s == null) continue;

            String user = TrainingTurns.userTurn(s);
            String assistant;
            try {
                assistant = TrainingTurns.assistantContent(s.getAnnotation(), json);
            } catch (Exception e) {
                // A stored annotation that will not re-serialise is a bug in that sample, and it
                // should not stop every parse that happens to be nearest to it.
                log.warn("Few-shot: sample {} has an annotation that cannot be read, skipped", s.getId());
                continue;
            }

            int cost = user.length() + assistant.length();
            if (cost > remaining) continue;

            remaining -= cost;
            chosen.add(new Example(s.getId(), n.distance(), user, assistant));
        }

        // Greedy fill ran nearest-first; reverse so the nearest sits last, beside the question.
        Collections.reverse(chosen);
        return chosen;
    }
}
