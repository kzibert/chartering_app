package com.chartering.service.ask;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the answering model is told: the rules with today's date, and the numbered sources it may cite.
 */
class AskPromptTest {

    private static AskRetriever.Hit hit(long chunk, SourceKind kind, String title, LocalDateTime date, String content) {
        return new AskRetriever.Hit(chunk, kind, chunk * 10, title, date, content, 0.5);
    }

    @Test
    void theRulesNameTodaysDateAndHoldTheAnswerToTheSources() {
        String system = AskPrompt.system(LocalDate.of(2026, 10, 10));

        assertThat(system).contains("Today is 2026-10-10.");
        assertThat(system).contains("ONLY the numbered sources");
        assertThat(system).contains("Cite each source");
        assertThat(system).contains("say so plainly");
        assertThat(system).contains("data");
    }

    @Test
    void sourcesAreNumberedInRankOrderAndTheQuestionComesLast() {
        List<AskRetriever.Hit> sources = List.of(
                hit(1, SourceKind.MAIL, "Spot Adriatic", LocalDateTime.of(2026, 10, 8, 9, 0), "First passage."),
                hit(2, SourceKind.POST, "Ship.gr", null, "Second passage."));

        String user = AskPrompt.user(sources, "  Who has tonnage?  ");

        assertThat(user).contains("[1] email, 2026-10-08 - Spot Adriatic\nFirst passage.");
        assertThat(user).contains("[2] board post - Ship.gr\nSecond passage.");
        assertThat(user.indexOf("[1]")).isLessThan(user.indexOf("[2]"));
        assertThat(user).endsWith("Question: Who has tonnage?");
    }

    @Test
    void aSnippetIsOneShortLine() {
        String snippet = AskPrompt.snippet("Line one\n\n   line   two " + "z".repeat(400));

        assertThat(snippet).startsWith("Line one line two ");
        assertThat(snippet).doesNotContain("\n");
        assertThat(snippet.length()).isLessThanOrEqualTo(AskPrompt.SNIPPET_CHARS + 3);
    }
}
