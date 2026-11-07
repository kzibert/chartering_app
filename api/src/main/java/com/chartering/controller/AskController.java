package com.chartering.controller;

import com.chartering.dto.AskIndexStatusResponse;
import com.chartering.dto.AskRequest;
import com.chartering.dto.AskResponse;
import com.chartering.dto.AskStatusResponse;
import com.chartering.service.parser.EmbeddingClient;
import com.chartering.service.ask.AskFeature;
import com.chartering.service.ask.AskService;
import com.chartering.service.ask.SearchIndexService;
import com.chartering.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask the desk: a question answered from the desk's own mail, board posts, cargoes and vessel
 * positions, with the passages it was answered from.
 *
 * <p>Off unless both the parser and the embedding server are configured, in which case everything
 * except {@code /status} answers 404 ({@link AskFeature}). The status always answers, because the
 * tab asks it before it decides whether to exist.
 *
 * <p>Mail is answered only where the asker may open it: their own mailbox, and the messages the desk
 * shares. That is enforced in {@link com.chartering.service.ask.AskRetriever}, not here.
 */
@RestController
@RequestMapping("/api/v1/ask")
@RequiredArgsConstructor
@Tag(name = "Ask the desk",
        description = "Questions answered from the desk's own text, with citations. Local deployments "
                + "only; off unless PARSER_ENABLED and EMBEDDING_URL are both set.")
public class AskController {

    private final AskService ask;
    private final SearchIndexService index;
    private final AskFeature feature;
    private final EmbeddingClient embeddings;

    @PostMapping
    @Operation(summary = "Answer a question from the desk's text",
            description = "The answer cites the passages it was written from as [n], and sources lists "
                    + "them in that order. 409 while the parser sweep is using the model, 503 when the "
                    + "embedding server or the model cannot answer. 404 where the feature is off.")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest req) {
        return ResponseEntity.ok(ask.ask(req.question(), TenantContext.requireUser()));
    }

    @GetMapping("/status")
    @Operation(summary = "Whether this deployment has Ask the desk at all",
            description = "Always answers. enabled is false where PARSER_ENABLED or EMBEDDING_URL is not set.")
    public ResponseEntity<AskStatusResponse> status() {
        boolean enabled = feature.isEnabled();
        return ResponseEntity.ok(new AskStatusResponse(enabled, enabled ? embeddings.model() : null));
    }

    @PostMapping("/index")
    @Operation(summary = "Index this desk's text for search",
            description = "Starts a run on the worker and returns at once. 409 when this desk's run is "
                    + "already going. 404 where the feature is off.")
    public ResponseEntity<AskIndexStatusResponse> startIndex() {
        if (!index.start()) {
            throw new IllegalStateException("The desk's text is already being indexed. Watch "
                    + "GET /api/v1/ask/index until running is false.");
        }
        return ResponseEntity.accepted().body(index.status());
    }

    @GetMapping("/index")
    @Operation(summary = "How far this desk's index has got",
            description = "Indexed and pending source counts, and whether a run is going. 404 where the "
                    + "feature is off.")
    public ResponseEntity<AskIndexStatusResponse> indexStatus() {
        return ResponseEntity.ok(index.status());
    }
}
