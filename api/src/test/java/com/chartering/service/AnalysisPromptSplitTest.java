package com.chartering.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live prompt has not moved, and the training prompt is it plus one section.
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
 * swap is deliberate and this constant is updated in the same commit — which is exactly the
 * review this is asking for.
 */
class AnalysisPromptSplitTest {

    /**
     * SHA-256 of {@code SYSTEM_PROMPT} as the currently deployed finetune was trained on it.
     *
     * <p>Recorded when the training prompt was split out to collect company data, and verified
     * against the constant as it stood before that change.
     */
    private static final String LIVE_PROMPT_SHA256 =
            "9714dcc2c6aa5d7e9da941113f178d520eb32db19d6d8faf4db14a3b5adc60fa";

    @Test
    void theLivePromptIsStillTheOneTheModelWasTrainedOn() {
        assertThat(sha256(AnalysisAnnotationTemplates.SYSTEM_PROMPT))
                .as("SYSTEM_PROMPT is what EmailParserClient sends to a model finetuned on it. "
                        + "If this changed on purpose, a model trained on the new wording has to "
                        + "ship with it and parser/extraction-schema.json has to be regenerated "
                        + "from chartering-ml — see AnalysisAnnotationTemplates.")
                .isEqualTo(LIVE_PROMPT_SHA256);
    }

    /**
     * The half they share is genuinely shared rather than copied, which is what stops the two
     * drifting apart in the rules that matter to both.
     */
    @Test
    void theTrainingPromptIsTheLiveOnePlusTheCompanySection() {
        String live = AnalysisAnnotationTemplates.SYSTEM_PROMPT;
        String training = AnalysisAnnotationTemplates.TRAINING_SYSTEM_PROMPT;

        assertThat(training).isNotEqualTo(live);
        // Both end with the same instruction, and everything before the company section is
        // word for word the live prompt's rules.
        String tail = "Return the JSON object and nothing else.";
        assertThat(live).endsWith(tail);
        assertThat(training).endsWith(tail);
        assertThat(training).startsWith(live.substring(0, live.length() - tail.length() - 1));
    }

    @Test
    void onlyTheTrainingPromptAsksForTheCompany() {
        // The live model cannot return a company - the JSON grammar it is served behind has no
        // such field - so asking it for one would be prompt it has never seen, for an answer it
        // could not give. That is the whole reason the two are separate constants.
        assertThat(AnalysisAnnotationTemplates.SYSTEM_PROMPT).doesNotContain("\"company\"");
        assertThat(AnalysisAnnotationTemplates.TRAINING_SYSTEM_PROMPT).contains("\"company\"");
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
