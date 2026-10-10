package com.chartering.service.ask;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a document is cut into the passages a question is answered from. The properties that matter:
 * a cut lands between paragraphs when it can, no line is split when it fits, every chunk says what it
 * is about, and nothing is lost.
 */
class TextChunkerTest {

    @Test
    void aShortBodyIsOneChunkUnderItsHeader() {
        List<String> chunks = TextChunker.chunks("Subject: Spot", "SPOT ADRIATIC 1/3 SEPT\n\nAll good.");

        assertThat(chunks).containsExactly("Subject: Spot\n\nSPOT ADRIATIC 1/3 SEPT\n\nAll good.");
    }

    @Test
    void aBlankBodyIsTheHeaderAloneSoTheMessageIsStillFoundBySubject() {
        assertThat(TextChunker.chunks("Subject: Only a subject", "   ")).containsExactly("Subject: Only a subject");
    }

    @Test
    void aLongBodyIsCutBetweenParagraphsAndEveryChunkCarriesTheHeader() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) body.append("\n\n");
            body.append("P").append(i).append(' ').append("x".repeat(290));
        }

        List<String> chunks = TextChunker.chunks("Subject: Long", body.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk).startsWith("Subject: Long\n\n");
            assertThat(chunk.length()).isLessThanOrEqualTo(TextChunker.MAX_CHARS);
            // A cut never splits a paragraph: every paragraph in a chunk starts with its own label.
            for (String paragraph : chunk.substring("Subject: Long\n\n".length()).split("\n\n")) {
                assertThat(paragraph).startsWith("P");
            }
        }
        // Nothing lost: each of the twelve paragraphs is whole in exactly one chunk.
        for (int i = 0; i < 12; i++) {
            String label = "P" + i + " " + "x".repeat(290);
            assertThat(chunks.stream().filter(c -> c.contains(label)).count()).isEqualTo(1);
        }
    }

    @Test
    void aParagraphTooLongForOneChunkIsSplitOnItsLinesNotInsideThem() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) lines.add("line " + i + " " + "y".repeat(90));
        String body = String.join("\n", lines);

        List<String> chunks = TextChunker.chunks("Subject: Lines", body);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (String line : lines) {
            assertThat(chunks.stream().filter(c -> c.contains(line)).count()).isEqualTo(1);
        }
    }

    @Test
    void aSingleLineLongerThanAChunkIsCutButNothingIsLost() {
        String line = "ab".repeat(1_500);

        List<String> chunks = TextChunker.chunks("H", line);

        assertThat(chunks.size()).isGreaterThan(1);
        StringBuilder rebuilt = new StringBuilder();
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(TextChunker.MAX_CHARS);
            assertThat(chunk).startsWith("H\n\n");
            rebuilt.append(chunk.substring("H\n\n".length()));
        }
        assertThat(rebuilt.toString()).isEqualTo(line);
    }

    @Test
    void aLongHeaderStillLeavesTheBodyRoomAndCountsAgainstTheLimit() {
        String header = "Subject: " + "h".repeat(1_100);
        String body = "word ".repeat(800);

        List<String> chunks = TextChunker.chunks(header, body);

        assertThat(chunks.size()).isGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk).startsWith(header + "\n\n");
            assertThat(chunk.length()).isLessThanOrEqualTo(header.length() + 2 + TextChunker.MIN_BODY_CHARS
                    + TextChunker.MAX_CHARS);
        }
    }

    @Test
    void aRecordKeptWholeIsHeaderAndBodyWithNoSplit() {
        assertThat(TextChunker.whole("Cargo #7 (OPEN)", "Commodity: Wheat\n\nLoad: Chornomorsk"))
                .isEqualTo("Cargo #7 (OPEN)\n\nCommodity: Wheat\n\nLoad: Chornomorsk");
    }
}
