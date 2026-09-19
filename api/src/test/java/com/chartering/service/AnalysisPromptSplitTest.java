package com.chartering.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live prompt is the one the served model was trained on, and the grammar lets it answer.
 *
 * <p><b>Why a hash and not a readable assertion.</b> What has to be guaranteed here is not that
 * the prompt says something in particular — it is that it says <em>exactly what the running
 * model was finetuned against</em>, down to the whitespace. A test that spelled the text out
 * again would be a second copy to keep in step, and the copy would be edited alongside the
 * original by whoever changed it. A digest cannot be edited into agreement by accident.
 *
 * <p>So this fails loudly the moment the live prompt changes, and the failure is the message:
 * the model was measured under the old wording, and changing it without shipping a model
 * trained on the new one spends today's accuracy for nothing. When that model does ship, the
 * swap is deliberate and this constant is updated in the same commit — as it was for the V3
 * parser, which moved it from {@code 9714dcc2…} (rules only, V1 and V2) to the prompt with the
 * company section.
 */
class AnalysisPromptSplitTest {

    /**
     * SHA-256 of {@code SYSTEM_PROMPT} as the served V3 finetune was trained on it - the system
     * turn of every line of chartering-ml's {@code data/snapshots/20260919-1134.jsonl}.
     */
    private static final String LIVE_PROMPT_SHA256 =
            "ef478cb5dec96b1917c81a517a2d5564fe4565560a40c60e1f3fee2be4637d04";

    @Test
    void theLivePromptIsTheOneTheServedModelWasTrainedOn() {
        assertThat(sha256(AnalysisAnnotationTemplates.SYSTEM_PROMPT))
                .as("SYSTEM_PROMPT is what EmailParserClient sends to a model finetuned on it. "
                        + "If this changed on purpose, a model trained on the new wording has to "
                        + "ship with it and parser/extraction-schema.json has to be regenerated "
                        + "from chartering-ml — see AnalysisAnnotationTemplates.")
                .isEqualTo(LIVE_PROMPT_SHA256);
    }

    @Test
    void theCorpusIsExportedUnderTheLivePrompt() {
        // Until the next section is added for a future model, the corpus is collected under
        // exactly what the served model is sent - a split here is deliberate, never drift.
        assertThat(AnalysisAnnotationTemplates.TRAINING_SYSTEM_PROMPT)
                .isEqualTo(AnalysisAnnotationTemplates.SYSTEM_PROMPT);
        assertThat(AnalysisAnnotationTemplates.SYSTEM_PROMPT).endsWith("Return the JSON object and nothing else.");
    }

    @Test
    void thePromptAsksForTheCompanyAndTheGrammarLetsTheModelGiveOne() throws Exception {
        // The prompt and the grammar move together or not at all: a prompt asking for a field the
        // JSON schema forbids is an instruction the model is physically unable to follow, and a
        // schema requiring one the prompt never mentions forces it to invent an answer.
        assertThat(AnalysisAnnotationTemplates.SYSTEM_PROMPT).contains("\"company\"");
        try (InputStream in = getClass().getResourceAsStream("/parser/extraction-schema.json")) {
            JsonNode schema = new ObjectMapper().readTree(in);
            assertThat(schema.path("properties").has("company")).isTrue();
            assertThat(schema.path("required").toString()).contains("\"company\"");
            JsonNode company = schema.path("properties").path("company").path("properties");
            assertThat(company.has("people")).isTrue();
            assertThat(company.has("contacts")).isTrue();
        }
    }

    @Test
    void everyTemplateOffersTheCompanyBlockToFillIn() {
        // Including OTHER, empty. A model shown a signature only on emails that had a cargo in
        // them would learn that the two go together; the empty block is that lesson's other half.
        assertThat(AnalysisAnnotationTemplates.all()).isNotEmpty();
        AnalysisAnnotationTemplates.all().forEach((label, skeleton) ->
                assertThat(skeleton).as("template for %s", label).contains("\"company\""));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
