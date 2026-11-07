package com.chartering.service.ask;

import com.chartering.config.ParserProperties;
import com.chartering.service.ParserSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The answering request as the server receives it, and how its replies are read. No HTTP: the
 * request body is built and the reply is interpreted by the two package-private methods.
 */
class AskModelClientTest {

    private final ObjectMapper json = new ObjectMapper();
    private AskModelClient client;

    @BeforeEach
    void setUp() {
        client = new AskModelClient(new ParserProperties(), mock(ParserSettings.class), json);
    }

    @Test
    void theRequestAsksForTheBaseModelWithTheParserAdapterSwitchedOff() throws Exception {
        JsonNode body = json.readTree(client.requestBody(null, "SYSTEM RULES", "THE QUESTION"));

        assertThat(body.path("lora").get(0).path("id").asInt()).isZero();
        assertThat(body.path("lora").get(0).path("scale").asDouble()).isZero();
        assertThat(body.path("messages")).hasSize(2);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("SYSTEM RULES");
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("THE QUESTION");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(AskModelClient.MAX_TOKENS);
        assertThat(body.path("temperature").asDouble()).isEqualTo(AskModelClient.TEMPERATURE);
        assertThat(body.has("response_format")).isFalse();
        assertThat(body.has("model")).isFalse();
    }

    @Test
    void aConfiguredModelNameIsSentAndABlankOneIsNot() throws Exception {
        assertThat(json.readTree(client.requestBody("qwen3", "s", "u")).path("model").asText()).isEqualTo("qwen3");
        assertThat(json.readTree(client.requestBody("", "s", "u")).has("model")).isFalse();
    }

    @Test
    void aServerWithoutTheAdapterIsReportedAsTheConfigurationItIs() {
        assertThatThrownBy(() -> client.reply(400, "{\"error\":\"lora adapter id 0 is not loaded\"}"))
                .isInstanceOf(AskUnavailableException.class)
                .hasMessageContaining("LoRA compose file");
    }

    @Test
    void anyOtherErrorKeepsItsStatusAndWhatTheServerSaid() {
        assertThatThrownBy(() -> client.reply(500, "out of memory"))
                .isInstanceOf(AskUnavailableException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("out of memory");
    }

    @Test
    void theAnswerIsTheMessageContentStripped() {
        String answer = client.reply(200, "{\"choices\":[{\"message\":{\"content\":\" Adriatic is open [1]. \"}}]}");

        assertThat(answer).isEqualTo("Adriatic is open [1].");
    }

    @Test
    void aReplyWithNoTextIsAnError() {
        assertThatThrownBy(() -> client.reply(200, "{\"choices\":[]}"))
                .isInstanceOf(AskUnavailableException.class);
    }
}
