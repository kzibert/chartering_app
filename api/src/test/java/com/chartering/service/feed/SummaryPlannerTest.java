package com.chartering.service.feed;

import com.chartering.model.FeedSummaryStrategy;
import com.chartering.service.feed.FeedItemSelector.Candidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fitting rules, with a counter that calls four characters a token so the arithmetic can be
 * followed by hand and no model server is needed.
 */
class SummaryPlannerTest {

    private static final TokenCounter FOUR_CHARS = text -> (text.length() + 3) / 4;

    /** Window 1,000: 668 tokens of material for the summary, 768 for a notes call. */
    private static SummaryPlanner.Budget budget(int maxCalls) {
        return new SummaryPlanner.Budget(1_000, 50, 50, 200, 100, maxCalls);
    }

    private static List<Candidate> candidates(int n, int chars) {
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String source = "Source " + (i % 3);
            list.add(new Candidate((long) i, source, LocalDateTime.of(2026, 9, 14, 8, 0), null,
                    ("item " + i + " ").repeat(chars / 7 + 1).substring(0, chars), 100 - i));
        }
        return list;
    }

    @Test
    void theBudgetIsTheWindowLessPromptAnswerAndMargin() {
        assertThat(budget(12).forSummary()).isEqualTo(1_000 - 50 - 200 - 82);
        assertThat(budget(12).forNotes()).isEqualTo(1_000 - 50 - 100 - 82);
    }

    @Test
    void everythingThatFitsGoesInOnePass() {
        var plan = SummaryPlanner.plan(candidates(3, 400), budget(12), FOUR_CHARS);

        assertThat(plan.strategy()).isEqualTo(FeedSummaryStrategy.SINGLE_PASS);
        assertThat(plan.used()).hasSize(3);
        assertThat(plan.dropped()).isZero();
        assertThat(plan.estimatedCalls()).isEqualTo(1);
    }

    @Test
    void tooMuchForOnePassBecomesBatchesThatEachFitANotesCall() {
        var plan = SummaryPlanner.plan(candidates(20, 400), budget(12), FOUR_CHARS);

        assertThat(plan.strategy()).isEqualTo(FeedSummaryStrategy.MAP_REDUCE);
        assertThat(plan.used()).hasSize(20);
        assertThat(plan.batches()).hasSizeGreaterThan(1)
                .allSatisfy(b -> assertThat(FOUR_CHARS.count(b)).isLessThanOrEqualTo(budget(12).forNotes()));
        assertThat(plan.estimatedCalls()).isLessThanOrEqualTo(12);
    }

    @Test
    void anItemLargerThanABatchIsCutIntoPartsThatFit() {
        String paragraph = "Grain from the Danube to Egypt was quoted about $110/t by coaster this week.";
        String huge = String.join("\n\n", java.util.Collections.nCopies(80, paragraph));

        var parts = SummaryPlanner.pack(List.of(huge), 300, FOUR_CHARS);

        assertThat(parts).hasSizeGreaterThan(1)
                .allSatisfy(p -> assertThat(FOUR_CHARS.count(p)).isLessThanOrEqualTo(300));
        assertThat(String.join("", parts).replace("\n", "").replace("---", ""))
                .contains("$110/t");
    }

    @Test
    void aLineWithNoBreaksIsStillCut() {
        var parts = SummaryPlanner.splitToFit("x".repeat(5_000), 200, FOUR_CHARS);

        assertThat(parts).allSatisfy(p -> assertThat(FOUR_CHARS.count(p)).isLessThanOrEqualTo(200));
        assertThat(String.join("", parts)).hasSize(5_000);
    }

    @Test
    void theCallCapDropsTheLowestRankedItemsAndSaysHowMany() {
        var ranked = candidates(60, 400);

        var plan = SummaryPlanner.plan(ranked, budget(3), FOUR_CHARS);

        assertThat(plan.dropped()).isPositive();
        assertThat(plan.used().size() + plan.dropped()).isEqualTo(60);
        assertThat(plan.estimatedCalls()).isLessThanOrEqualTo(3);
        // The best are the ones kept.
        assertThat(plan.used()).containsExactlyElementsOf(ranked.subList(0, plan.used().size()));
    }

    @Test
    void withOneCallTheBestItemsThatFitOnePassAreRead() {
        var plan = SummaryPlanner.plan(candidates(20, 400), budget(1), FOUR_CHARS);

        assertThat(plan.strategy()).isEqualTo(FeedSummaryStrategy.SINGLE_PASS);
        assertThat(plan.used()).isNotEmpty();
        assertThat(plan.dropped()).isEqualTo(20 - plan.used().size());
    }

    @Test
    void aPromptThatFillsTheWindowIsRefusedWithAReason() {
        var crowded = new SummaryPlanner.Budget(1_000, 900, 50, 200, 100, 12);

        assertThatThrownBy(() -> SummaryPlanner.plan(candidates(1, 100), crowded, FOUR_CHARS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no room for material");
    }

    @Test
    void theCallEstimateCountsCondensingLevels() {
        // Forty batches of 100-token notes is 4,000 tokens against 668: at least one condensing level.
        assertThat(SummaryPlanner.estimateCalls(40, budget(100))).isGreaterThan(41);
        assertThat(SummaryPlanner.estimateCalls(2, budget(100))).isEqualTo(3);
    }
}
