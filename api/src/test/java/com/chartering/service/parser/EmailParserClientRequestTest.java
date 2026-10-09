package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.model.AnalysisSample;
import com.chartering.service.AnalysisAnnotationTemplates;
import com.chartering.service.ParserSettings;
import com.chartering.service.TrainingTurns;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The bytes the model server is sent, with and without worked examples.
 *
 * <p>With no examples the request must be the one the model was measured under, byte for byte: the
 * system prompt and the question, nothing between them. With examples they go between the two, in
 * the export's own turns, so the model meets an example exactly as it met one in training.
 */
class EmailParserClientRequestTest {

    private final ObjectMapper json = new ObjectMapper();
    private ParserProperties props;
    private EmailParserClient client;

    @BeforeEach
    void setUp() {
        props = new ParserProperties();
        client = new EmailParserClient(props, mock(ParserSettings.class), json);
        // The grammar is read by @PostConstruct in the running application; do the same here so the
        // request carries the response_format it would carry in production.
        client.loadSchema();
    }

    private static AnalysisSample sample(long id, String subject, String body, String annotation) {
        AnalysisSample s = new AnalysisSample();
        s.setId(id);
        s.setSubject(subject);
        s.setSentAt(LocalDateTime.of(2026, 9, 5, 9, 30));
        s.setBodyText(body);
        s.setAnnotation(annotation);
        return s;
    }

    private static FewShotRetriever.Example example(AnalysisSample s, double distance, ObjectMapper json)
            throws Exception {
        return new FewShotRetriever.Example(s.getId(), distance, TrainingTurns.userTurn(s),
                TrainingTurns.assistantContent(s.getAnnotation(), json));
    }

    @Test
    void withNoExamplesTheRequestIsTheMeasuredTwoMessageShapeExactly() throws Exception {
        String user = "Date: 2026-09-05\nSubject: OPEN 07/10 SEPTEMBER\n\nMV EXAMPLE open Constanza.";

        String body = client.requestBody(List.of(), user, "");

        // The prefix is pinned byte for byte: field order, the system prompt through the real
        // serialiser, the user turn, then the sampling settings. Nothing about it may move without
        // this test saying so.
        String expectedPrefix = "{\"messages\":["
                + "{\"role\":\"system\",\"content\":" + json.writeValueAsString(AnalysisAnnotationTemplates.SYSTEM_PROMPT) + "},"
                + "{\"role\":\"user\",\"content\":" + json.writeValueAsString(user) + "}],"
                + "\"temperature\":0,"
                + "\"max_tokens\":" + props.getMaxTokens() + ","
                + "\"stream\":false,"
                + "\"response_format\":";
        assertThat(body).startsWith(expectedPrefix);

        JsonNode messages = json.readTree(body).path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
    }

    @Test
    void examplesGoBetweenTheSystemPromptAndTheQuestionInOrder() throws Exception {
        AnalysisSample first = sample(1, "WHEAT CARGO", "25,000 MT wheat, Odessa to Spain.",
                "{\"type\":\"cargo\"}");
        AnalysisSample second = sample(2, "OPEN", "MV EXAMPLE open Constanza 07/10 Sept.",
                "{\"type\":\"vessel_opening\",\"vessels\":[]}");
        List<FewShotRetriever.Example> examples = List.of(
                example(first, 0.4, json),
                example(second, 0.1, json));
        String question = "Date: 2026-09-09\nSubject: NEW\n\nThe question.";

        JsonNode messages = json.readTree(client.requestBody(examples, question, "")).path("messages");

        assertThat(messages).hasSize(6);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText())
                .isEqualTo(AnalysisAnnotationTemplates.SYSTEM_PROMPT);

        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).path("content").asText()).isEqualTo(TrainingTurns.userTurn(first));
        assertThat(messages.get(2).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).path("content").asText())
                .isEqualTo(TrainingTurns.assistantContent(first.getAnnotation(), json));

        assertThat(messages.get(3).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(3).path("content").asText()).isEqualTo(TrainingTurns.userTurn(second));
        assertThat(messages.get(4).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(4).path("content").asText())
                .isEqualTo(TrainingTurns.assistantContent(second.getAnnotation(), json));

        assertThat(messages.get(5).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(5).path("content").asText()).isEqualTo(question);
    }
}
