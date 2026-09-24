package com.chartering.service;

import com.chartering.model.AnalysisLabel;
import com.chartering.model.AnalysisSample;
import com.chartering.model.AnalysisStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Every exported line asks for exactly what its answer contains.
 *
 * <p>The corpus is annotated and exported against the prompt the served parser is sent, which
 * since the V3 parser asks for the firm that signed the email - so the company block travels into
 * the file with the rest of the answer. The pairing is the point, and the failure it guards
 * against is silent: a file whose instruction asks for a section every answer omits trains a model
 * to ignore that section, and a file whose answers carry a section the instruction never mentions
 * trains it to emit fields unpredictably. Every test that does not look at the file still passes
 * in both cases. On 2026-09-17 none of the 168 reviewed annotations carried a company block,
 * which is how the mismatch was first found; on 2026-09-19 the corpus was labelled for it.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisExportPromptTest {

    @Mock
    private AnalysisService analysis;

    @Spy
    private ObjectMapper json = new ObjectMapper();

    @InjectMocks
    private AnalysisExportService export;

    private static AnalysisSample sample(String annotation) {
        AnalysisSample s = new AnalysisSample();
        s.setId(1L);
        s.setLabel(AnalysisLabel.VESSEL_OPENING);
        s.setStatus(AnalysisStatus.READY);
        s.setSubject("MV EXAMPLE - OPEN 07/10 SEPTEMBER");
        s.setSentAt(LocalDateTime.of(2026, 9, 5, 9, 30));
        s.setBodyText("MV EXAMPLE, 6,000 DWT, open Constanza 07/10 September.");
        s.setAnnotation(annotation);
        return s;
    }

    private JsonNode exportOneLine(String annotation) throws Exception {
        when(analysis.readyForExport()).thenReturn(List.of(sample(annotation)));
        String jsonl = export.toJsonl();
        assertThat(jsonl).endsWith("\n");
        assertThat(jsonl.strip()).doesNotContain("\n");
        return new ObjectMapper().readTree(jsonl.strip());
    }

    @Test
    void theSystemTurnIsTheLivePromptAndAsksForTheCompany() throws Exception {
        JsonNode line = exportOneLine("{\"type\":\"vessel_opening\",\"vessels\":[]}");

        JsonNode system = line.path("messages").path(0);
        assertThat(system.path("role").asText()).isEqualTo("system");
        assertThat(system.path("content").asText())
                .as("the export trains the prompt the corpus actually answers")
                .isEqualTo(AnalysisAnnotationTemplates.SYSTEM_PROMPT)
                .contains("\"company\"");
    }

    @Test
    void anAnnotatedCompanyTravelsIntoTheFile() throws Exception {
        JsonNode line = exportOneLine("""
                {"type":"vessel_opening",
                 "cargoes":[],
                 "vessels":[{"name":"EXAMPLE","dwt":6000}],
                 "company":{"name":"Example Chartering","website":"example.example",
                            "people":[],"contacts":[]}}
                """);

        JsonNode answer = new ObjectMapper()
                .readTree(line.path("messages").path(2).path("content").asText());
        assertThat(answer.path("company").path("name").asText())
                .as("the instruction asks for the signing firm, so the answer carries it")
                .isEqualTo("Example Chartering");
        assertThat(answer.path("company").path("website").asText()).isEqualTo("example.example");
        assertThat(answer.path("type").asText()).isEqualTo("vessel_opening");
        assertThat(answer.path("vessels").path(0).path("name").asText()).isEqualTo("EXAMPLE");
        assertThat(answer.path("vessels").path(0).path("dwt").asInt()).isEqualTo(6000);
    }

    @Test
    void theUserTurnStillCarriesTheDateTheAnnotationsYearsComeFrom() throws Exception {
        JsonNode line = exportOneLine("{\"type\":\"vessel_opening\",\"vessels\":[]}");

        String user = line.path("messages").path(1).path("content").asText();
        assertThat(user).startsWith("Date: 2026-09-05\n");
        assertThat(user).contains("Subject: MV EXAMPLE - OPEN 07/10 SEPTEMBER");
    }
}
