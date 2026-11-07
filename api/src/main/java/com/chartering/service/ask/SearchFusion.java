package com.chartering.service.ask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the vector ranking and the keyword ranking into one, by reciprocal rank fusion.
 *
 * <p><b>Why rank rather than score.</b> The two sides measure different things: a cosine distance
 * and a {@code ts_rank_cd} are not on one scale, and mixing them would let whichever side happens to
 * produce bigger numbers decide the answer. A rank is on the same scale for both. A passage near the
 * top of both lists is near the top of the fusion; one near the top of only one list still gets a
 * place, which is the point of asking both.
 *
 * <p><b>k = 60</b>, the constant the method was published with. It flattens the head of each list so
 * that first place is not worth ten times fifth, which is what lets a passage that both sides rate
 * fourth beat one that only the keyword side rates first.
 *
 * <p><b>At most two passages from one record.</b> A long chain or a board post can be split into a
 * dozen chunks that all say the same thing, and without a cap the eight sources given to the model
 * would be one email eight times. Two is enough to show a record that answers in two places; the
 * rest of the budget goes to other records.
 *
 * <p>Pure, so the ordering and the cap are tested without a database.
 */
public final class SearchFusion {

    /** The rank constant of reciprocal rank fusion. */
    static final int K = 60;

    private SearchFusion() {
    }

    /** One candidate passage: its chunk id and the record it belongs to. */
    public record Candidate(long chunkId, SourceKind kind, long sourceId) {
    }

    /** A candidate with its fused score, in the order it was kept. */
    public record Fused(Candidate candidate, double score) {
    }

    /**
     * The fused ranking, best first, with at most {@code perSourceCap} passages from any one record
     * and at most {@code top} in all.
     *
     * @param rankings each list best first; a passage may appear in more than one
     */
    public static List<Fused> fuse(List<List<Candidate>> rankings, int perSourceCap, int top) {
        Map<Long, Candidate> byChunk = new HashMap<>();
        Map<Long, Double> scores = new HashMap<>();
        for (List<Candidate> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                Candidate c = ranking.get(i);
                byChunk.putIfAbsent(c.chunkId(), c);
                scores.merge(c.chunkId(), 1.0 / (K + i + 1), Double::sum);
            }
        }

        List<Map.Entry<Long, Double>> order = new ArrayList<>(scores.entrySet());
        // Ties go to the lower chunk id, so the same question gives the same answer every time.
        order.sort((a, b) -> {
            int byScore = Double.compare(b.getValue(), a.getValue());
            return byScore != 0 ? byScore : Long.compare(a.getKey(), b.getKey());
        });

        Map<String, Integer> kept = new LinkedHashMap<>();
        List<Fused> out = new ArrayList<>();
        for (Map.Entry<Long, Double> e : order) {
            if (out.size() >= top) break;
            Candidate c = byChunk.get(e.getKey());
            String record = c.kind() + ":" + c.sourceId();
            int count = kept.getOrDefault(record, 0);
            if (count >= perSourceCap) continue;
            kept.put(record, count + 1);
            out.add(new Fused(c, e.getValue()));
        }
        return out;
    }
}
