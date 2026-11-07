package com.chartering.service.ask;

import com.chartering.dto.AskResponse;
import com.chartering.service.parser.EmbeddingClient;
import com.chartering.service.parser.EmbeddingClient.EmbeddingUnavailableException;
import com.chartering.service.parser.ParserSweepService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The order of refusals and the shape of an answer: the sweep is checked before anything is asked,
 * a question with no passage never reaches the model, and a model refusal reaches the caller as it is.
 */
@ExtendWith(MockitoExtension.class)
class AskServiceTest {

    @Mock
    private AskFeature feature;

    @Mock
    private EmbeddingClient embeddings;

    @Mock
    private AskRetriever retriever;

    @Mock
    private AskModelClient answerer;

    @Mock
    private ParserSweepService sweep;

    private AskService service;

    @BeforeEach
    void wire() {
        service = new AskService(feature, embeddings, retriever, answerer, sweep);
    }

    @Test
    void whileTheParserSweepRunsTheQuestionIsRefusedBeforeAnythingIsAsked() {
        when(sweep.isRunning()).thenReturn(true);

        assertThatThrownBy(() -> service.ask("who is open?", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ask again in a minute");
        verifyNoInteractions(embeddings, retriever, answerer);
    }

    @Test
    void anUnreachableEmbeddingServerIsReportedAsUnavailableAndTheModelIsNotAsked() {
        when(embeddings.embed(anyString())).thenThrow(new EmbeddingUnavailableException("connection refused"));

        assertThatThrownBy(() -> service.ask("who is open?", 1L))
                .isInstanceOf(AskUnavailableException.class)
                .hasMessageContaining("connection refused");
        verifyNoInteractions(answerer);
    }

    @Test
    void aQuestionWithNoPassageIsAnsweredWithoutTheModel() {
        when(embeddings.embed(anyString())).thenReturn(new float[]{1f, 0f, 0f});
        when(embeddings.model()).thenReturn("m");
        when(retriever.search(anyString(), any(), anyString(), anyLong())).thenReturn(List.of());

        AskResponse response = service.ask("anything at all", 1L);

        assertThat(response.answer()).isEqualTo(AskService.NOTHING_FOUND);
        assertThat(response.sources()).isEmpty();
        verifyNoInteractions(answerer);
    }

    @Test
    void theAnswerCarriesItsSourcesInCitationOrderAndTheStrippedQuestionIsSearched() {
        AskRetriever.Hit first = new AskRetriever.Hit(1, SourceKind.MAIL, 11, "Spot Adriatic",
                LocalDateTime.of(2026, 10, 8, 9, 0), "Open Adriatic 1/3 SEPT.", 0.9);
        AskRetriever.Hit second = new AskRetriever.Hit(2, SourceKind.POST, 22, "Ship.gr", null,
                "Handysize open Med.", 0.4);
        when(embeddings.embed(anyString())).thenReturn(new float[]{1f, 0f, 0f});
        when(embeddings.model()).thenReturn("m");
        when(retriever.search(eq("who is open?"), any(), eq("m"), eq(7L))).thenReturn(List.of(first, second));
        when(answerer.complete(anyString(), anyString())).thenReturn("Adriatic is open [1].");
        when(answerer.modelName()).thenReturn("qwen3");

        AskResponse response = service.ask("  who is open?  ", 7L);

        assertThat(response.answer()).isEqualTo("Adriatic is open [1].");
        assertThat(response.model()).isEqualTo("qwen3");
        assertThat(response.sources())
                .extracting(AskResponse.Source::n, AskResponse.Source::kind, AskResponse.Source::id,
                        AskResponse.Source::date)
                .containsExactly(tuple(1, "MAIL", 11L, "2026-10-08"), tuple(2, "POST", 22L, null));
        verify(retriever).search(eq("who is open?"), any(), eq("m"), eq(7L));
    }

    @Test
    void aModelRefusalReachesTheCallerAsItIs() {
        AskRetriever.Hit hit = new AskRetriever.Hit(1, SourceKind.CARGO, 5, "Wheat", null, "Wheat 25000 MT.", 0.5);
        when(embeddings.embed(anyString())).thenReturn(new float[]{1f, 0f, 0f});
        when(embeddings.model()).thenReturn("m");
        when(retriever.search(anyString(), any(), anyString(), anyLong())).thenReturn(List.of(hit));
        when(answerer.complete(anyString(), anyString()))
                .thenThrow(new AskUnavailableException("needs the adapter"));

        assertThatThrownBy(() -> service.ask("wheat?", 1L))
                .isInstanceOf(AskUnavailableException.class)
                .hasMessageContaining("adapter");
    }
}
