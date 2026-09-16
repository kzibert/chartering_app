package com.chartering.service.feed;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedSettingsTest {

    private static FeedSettings.Update window(int contextWindow, Integer summary) {
        return new FeedSettings.Update(contextWindow, summary, null, null, null, null, null, null);
    }

    @Test
    void theDefaultsFitTheModelAsItIsServed() {
        assertThatCode(() -> FeedSettings.validate(window(8_192, null), FeedSettings.defaults()))
                .doesNotThrowAnyException();
    }

    @Test
    void aWindowThatCannotHoldTheAnswerAndSomeMaterialIsRefused() {
        // Saved, this would fail every run - a setting that looks saved and is not.
        assertThatThrownBy(() -> FeedSettings.validate(window(2_048, 2_000), FeedSettings.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must leave at least");
    }

    @Test
    void outOfRangeNumbersSayWhatTheRangeIs() {
        var tooOften = new FeedSettings.Update(null, null, null, null, null, 5_000, null, null);

        assertThatThrownBy(() -> FeedSettings.validate(tooOften, FeedSettings.defaults()))
                .hasMessageContaining("between 0 and 1440");
    }

    @Test
    void placeholdersAreFilledAndAnEmptyKeywordListSaysSo() {
        String rendered = FeedPrompts.render("{topic} | {keywords} | {today} | {period}",
                "Grain", List.of(), LocalDate.of(2026, 9, 15), "14 Sep 2026 to 15 Sep 2026");

        assertThat(rendered).isEqualTo("Grain | (none) | 2026-09-15 | 14 Sep 2026 to 15 Sep 2026");
    }
}
