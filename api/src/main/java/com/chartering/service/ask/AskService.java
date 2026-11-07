package com.chartering.service.ask;

import com.chartering.dto.AskResponse;
import com.chartering.service.parser.EmbeddingClient;
import com.chartering.service.parser.EmbeddingClient.EmbeddingUnavailableException;
import com.chartering.service.parser.ParserSweepService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers a question from the desk's own text, with the passages it was answered from.
 *
 * <p><b>The model is not asked while the parser is reading mail.</b> Both use the one model server,
 * whose slots share one KV cache. A sweep request and a question together can exceed it, which
 * happened on 2026-10-10, so while a sweep runs the question is refused with 409 and asked again. A
 * sweep is minutes at most, and a refusal the person can see is better than a server that fails both.
 *
 * <p><b>No passage, no model call.</b> With nothing to answer from the model would only be asked to
 * say so, at GPU cost, and it might say something else. The answer then says plainly that nothing on
 * the desk matches, and no sources are returned.
 *
 * <p>The question itself is never logged above debug: it is a person's question about their clients.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AskService {

    /** Nomic's prefix for the side that asks. The stored passages carry {@link SearchIndexService#DOCUMENT_PREFIX}. */
    static final String QUERY_PREFIX = "search_query: ";

    static final String NOTHING_FOUND = "Nothing on the desk's mail, boards, cargoes or vessel positions "
            + "matches that question.";

    private final AskFeature feature;
    private final EmbeddingClient embeddings;
    private final AskRetriever retriever;
    private final AskModelClient answerer;
    private final ParserSweepService sweep;

    /**
     * The answer to one question for the given person.
     *
     * @throws IllegalStateException   409 while the parser sweep is using the model
     * @throws AskUnavailableException 503 when the embedding server or the model cannot answer
     */
    public AskResponse ask(String question, long userId) {
        feature.requireEnabled();
        if (sweep.isRunning()) {
            throw new IllegalStateException("The parser sweep is using the model; ask again in a minute.");
        }
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("The question is blank.");
        }
        log.debug("Ask: {} characters, user {}", q.length(), userId);

        float[] query;
        try {
            query = embeddings.embed(QUERY_PREFIX + q);
        } catch (EmbeddingUnavailableException e) {
            throw new AskUnavailableException("The embedding server did not answer, so the desk's text cannot "
                    + "be searched right now: " + e.getMessage(), e);
        }

        List<AskRetriever.Hit> hits = retriever.search(q, query, embeddings.model(), userId);
        if (hits.isEmpty()) {
            return new AskResponse(NOTHING_FOUND, List.of(), null);
        }

        String answer = answerer.complete(AskPrompt.system(LocalDate.now()), AskPrompt.user(hits, q));
        return new AskResponse(answer, sources(hits), answerer.modelName());
    }

    private static List<AskResponse.Source> sources(List<AskRetriever.Hit> hits) {
        List<AskResponse.Source> out = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            AskRetriever.Hit h = hits.get(i);
            out.add(new AskResponse.Source(i + 1, h.kind().name(), h.sourceId(), h.title(),
                    h.date() == null ? null : h.date().toLocalDate().toString(),
                    AskPrompt.snippet(h.content())));
        }
        return out;
    }
}
