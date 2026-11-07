package com.chartering.service.ask;

import com.chartering.service.ask.SearchFusion.Candidate;
import com.chartering.service.ask.SearchFusion.Fused;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reciprocal rank fusion of the vector and keyword rankings, and the cap on passages from one record.
 */
class SearchFusionTest {

    private static Candidate chunk(long id, long record) {
        return new Candidate(id, SourceKind.MAIL, record);
    }

    private static List<Long> ids(List<Fused> fused) {
        return fused.stream().map(f -> f.candidate().chunkId()).toList();
    }

    @Test
    void aPassageNearTheTopOfBothListsBeatsOneNearTheTopOfOnlyOne() {
        List<Candidate> byVector = List.of(chunk(1, 1), chunk(2, 2), chunk(3, 3));
        List<Candidate> byWords = List.of(chunk(4, 4), chunk(2, 2), chunk(5, 5));

        List<Fused> fused = SearchFusion.fuse(List.of(byVector, byWords), 2, 8);

        // Chunk 2 is second in both lists; chunks 1 and 4 are first in only one of them.
        assertThat(ids(fused).get(0)).isEqualTo(2L);
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
    }

    @Test
    void noMoreThanTwoPassagesFromOneRecordReachTheAnswer() {
        List<Candidate> byVector = new ArrayList<>();
        for (long id = 1; id <= 4; id++) byVector.add(chunk(id, 7));
        byVector.add(chunk(9, 8));

        List<Fused> fused = SearchFusion.fuse(List.of(byVector, List.of(chunk(1, 7))), 2, 8);

        assertThat(ids(fused)).containsExactly(1L, 2L, 9L);
    }

    @Test
    void theTopLimitIsRespected() {
        List<Candidate> many = new ArrayList<>();
        for (long id = 1; id <= 20; id++) many.add(chunk(id, id));

        List<Fused> fused = SearchFusion.fuse(List.of(many), 2, 8);

        assertThat(fused).hasSize(8);
        assertThat(ids(fused)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void equalScoresAreOrderedByChunkIdSoTheSameQuestionGivesTheSameAnswer() {
        List<Fused> fused = SearchFusion.fuse(List.of(List.of(chunk(5, 5)), List.of(chunk(3, 3))), 2, 8);

        assertThat(ids(fused)).containsExactly(3L, 5L);
    }

    @Test
    void noRankingsGiveNoPassages() {
        assertThat(SearchFusion.fuse(List.of(List.of(), List.of()), 2, 8)).isEmpty();
    }
}
