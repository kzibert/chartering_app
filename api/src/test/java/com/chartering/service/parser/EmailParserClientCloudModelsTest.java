package com.chartering.service.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailParserClientCloudModelsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void listsChatModelsSortedAndLeavesOutSpeechModerationAndInactive() throws Exception {
        // Groq's own shape, trimmed: chat models beside speech, TTS and guard models.
        String body = """
                {"object":"list","data":[
                  {"id":"llama-3.3-70b-versatile","owned_by":"Meta","active":true,"context_window":131072},
                  {"id":"whisper-large-v3","owned_by":"OpenAI","active":true,"context_window":448},
                  {"id":"playai-tts","owned_by":"PlayAI","active":true,"context_window":8192},
                  {"id":"meta-llama/llama-guard-4-12b","owned_by":"Meta","active":true},
                  {"id":"openai/gpt-oss-20b","owned_by":"OpenAI","active":true,"context_window":131072},
                  {"id":"retired-model","owned_by":"Someone","active":false},
                  {"id":"Gemma2-9b-it"}
                ]}
                """;

        var models = EmailParserClient.readModels(json.readTree(body));

        assertThat(models).extracting(EmailParserClient.CloudModel::id)
                .containsExactly("Gemma2-9b-it", "llama-3.3-70b-versatile", "openai/gpt-oss-20b");
        assertThat(models.get(0).ownedBy()).isNull();
        assertThat(models.get(0).contextWindow()).isNull();
        assertThat(models.get(1).contextWindow()).isEqualTo(131072);
    }

    @Test
    void refusesABodyWithNoDataArray() throws Exception {
        assertThatThrownBy(() -> EmailParserClient.readModels(json.readTree("{\"error\":\"nope\"}")))
                .isInstanceOf(EmailParserClient.ParserUnavailableException.class);
    }
}
