package com.chartering.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Taking out of the model's answer the one character the database will not hold.
 *
 * <p>Not a hypothetical. Three messages failed on it with
 * {@code invalid byte sequence for encoding "UTF8": 0x00} — a NUL inside an extracted vessel
 * name, which travelled as an ordinary field value into the first query that touched it and
 * failed the whole parse of an email the model had in fact read correctly.
 *
 * <p>The escape is written here as {@code (char) 0} rather than spelled out, because a
 * u0000 escape sequence in a Java source file is turned into a real NUL by the compiler's
 * own preprocessing — inside comments and string literals alike — which is a good way to
 * produce a file that will not compile while looking entirely reasonable.
 */
class EmailParseRunnerTest {

    private static final char NUL = (char) 0;

    /**
     * The six characters the model actually emits, built rather than written.
     *
     * <p>A u-escape spelled out in a Java source file is replaced by the character it names
     * before the compiler sees the file — inside string literals and comments alike — so
     * writing it here would have put a real NUL in this test rather than the escape the test
     * is about, and the JSON would then not parse at all.
     */
    private static final String ESCAPED_NUL = "\\" + "u0000";

    private final ObjectMapper json = new ObjectMapper();
    private final EmailParseRunner runner = new EmailParseRunner(null, null, null, null, json);

    @Test
    void takesTheNulOutOfAValueNestedAnywhereInTheAnswer() throws Exception {
        // What the model actually produced: a JSON escape, which Jackson decodes into a real
        // NUL like any other character. The name reaches a query and Postgres refuses it.
        String answer = "{\"type\":\"vessel_opening\",\"vessels\":[{\"name\":\"" + ESCAPED_NUL + ESCAPED_NUL + "AB\"}]}";

        JsonNode clean = runner.scrubbed(json.readTree(answer));

        assertThat(clean.path("vessels").path(0).path("name").asText()).isEqualTo("AB");
        assertThat(clean.toString()).doesNotContain(String.valueOf(NUL));
    }

    @Test
    void bindsToAnExtractionWithTheNameUsable() throws Exception {
        String answer = "{\"type\":\"vessel_opening\",\"vessels\":"
                + "[{\"name\":\"HACI" + ESCAPED_NUL + " HILMI\",\"dwt\":6976}]}";

        Extraction e = json.treeToValue(runner.scrubbed(json.readTree(answer)), Extraction.class);

        // The four characters a suggestion query takes a prefix from - the exact value that
        // was being sent to Postgres.
        assertThat(e.vesselsOrEmpty().get(0).name()).isEqualTo("HACI HILMI");
    }

    @Test
    void leavesEverythingElseExactlyAsTheModelWroteIt() throws Exception {
        // Only the NUL. Tidying whitespace or punctuation here would be quietly editing the
        // one record of what the model said.
        String answer = "{\"type\":\"other\",\"summary\":\"  W.MED / 3 x 10T  e\"}";

        JsonNode clean = runner.scrubbed(json.readTree(answer));

        assertThat(clean.path("summary").asText()).isEqualTo("  W.MED / 3 x 10T  e");
    }

    @Test
    void leavesAStringWithNothingWrongWithItAlone() {
        assertThat(EmailParseRunner.storable("HACI HILMI-II")).isEqualTo("HACI HILMI-II");
        assertThat(EmailParseRunner.storable(null)).isNull();
        assertThat(EmailParseRunner.storable("A" + NUL + "B")).isEqualTo("AB");
    }
}
