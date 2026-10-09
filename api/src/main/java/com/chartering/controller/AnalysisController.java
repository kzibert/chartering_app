package com.chartering.controller;

import com.chartering.dto.AnalysisCaptureRequest;
import com.chartering.dto.AnalysisCaptureResponse;
import com.chartering.dto.AnalysisEmbeddingStatusResponse;
import com.chartering.dto.AnalysisFewShotRequest;
import com.chartering.dto.AnalysisFewShotResponse;
import com.chartering.dto.AnalysisPasteRequest;
import com.chartering.dto.AnalysisSampleDetailResponse;
import com.chartering.dto.AnalysisSampleResponse;
import com.chartering.dto.AnalysisSampleUpdateRequest;
import com.chartering.dto.AnalysisStatusResponse;
import com.chartering.dto.PageResponse;
import com.chartering.model.AnalysisLabel;
import com.chartering.model.AnalysisStatus;
import com.chartering.service.AnalysisExportService;
import com.chartering.service.AnalysisService;
import com.chartering.service.AnalysisService.SampleFilter;
import com.chartering.service.ParserSettings;
import com.chartering.service.SampleEmbeddingIndexer;
import com.chartering.service.parser.EmbeddingClient;
import com.chartering.service.parser.FewShotRetriever;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The email-analysis workbench: mail kept as training data for a model that reads cargo
 * offers and vessel opening positions.
 *
 * <p><b>A local-only feature.</b> {@code ANALYSIS_ENABLED} is false on the hosted deployment
 * and true in the compose environment, and everything here except {@code /status} answers
 * 404 when it is off. The status endpoint always answers, because the UI asks it to decide
 * whether the tab exists at all.
 */
@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
@Tag(name = "Analysis",
        description = "Incoming mail kept and labelled as finetuning data (local deployments only)")
public class AnalysisController {

    private final AnalysisService analysis;
    private final AnalysisExportService export;
    private final SampleEmbeddingIndexer indexer;
    private final EmbeddingClient embeddings;
    private final FewShotRetriever shots;
    private final ParserSettings parserSettings;

    @GetMapping("/status")
    @Operation(summary = "Whether this deployment runs the analysis workbench, and how the corpus stands",
            description = "The one endpoint here that answers when the feature is off — it "
                    + "returns enabled=false and nothing else, which is what tells the UI to "
                    + "leave the tab out of the navigation rather than show one that errors "
                    + "when clicked.")
    public ResponseEntity<AnalysisStatusResponse> status() {
        return ResponseEntity.ok(analysis.status());
    }

    @GetMapping("/embeddings")
    @Operation(summary = "How far this desk's retrieval index has got",
            description = "Counts READY samples with a vector computed from their current text, and "
                    + "whether an index run is going. 404 where ANALYSIS_ENABLED is off.")
    public ResponseEntity<AnalysisEmbeddingStatusResponse> embeddingStatus() {
        return ResponseEntity.ok(indexer.status());
    }

    @PostMapping("/embeddings/index")
    @Operation(summary = "Build or refresh the retrieval index for this desk",
            description = "Embeds every READY sample whose vector is missing or stale, on a worker "
                    + "thread; returns at once with 202 and the status. 409 while this desk's run is "
                    + "still going. 503 when EMBEDDING_URL is not set, 404 where ANALYSIS_ENABLED is off.")
    public ResponseEntity<AnalysisEmbeddingStatusResponse> startEmbeddingIndex() {
        indexer.requireConfigured();
        if (!indexer.start()) {
            throw new IllegalStateException("The retrieval index is already being built for this desk. "
                    + "Watch GET /api/v1/analysis/embeddings until running is false.");
        }
        return ResponseEntity.accepted().body(indexer.status());
    }

    /**
     * The worked examples the parser would be given for one email.
     *
     * <p>Exists for the chartering-ml harness, which scores the few-shot experiment offline. The
     * answer is produced by {@link FewShotRetriever} itself, so the examples a score was measured
     * against are the ones production would send, not a reimplementation of them. Gated like the
     * rest of this controller: 404 with ANALYSIS_ENABLED off, 503 without EMBEDDING_URL.
     */
    @PostMapping("/few-shot")
    @Operation(summary = "The examples the parser would be shown for one email",
            description = "The nearest READY samples on this desk, nearest last, as the user and "
                    + "assistant turns the parser inserts between the system prompt and the question. "
                    + "k and maxChars default to the installation's few-shot settings; minDistance "
                    + "(default 0) drops near-duplicates, and excludeSampleIds keeps a sample from "
                    + "being shown its own answer. 404 where ANALYSIS_ENABLED is off, 503 when "
                    + "EMBEDDING_URL is not set.")
    public ResponseEntity<AnalysisFewShotResponse> fewShot(@RequestBody AnalysisFewShotRequest body) {
        indexer.requireConfigured();
        ParserSettings.FewShot defaults = parserSettings.fewShot();
        int k = body.k() != null ? body.k() : defaults.examples();
        int maxChars = body.maxChars() != null ? body.maxChars() : defaults.maxChars();
        ParserSettings.requireFewShotExamples(k);
        ParserSettings.requireFewShotMaxChars(maxChars);
        List<FewShotRetriever.Example> examples = shots.examplesFor(
                body.subject() == null ? "" : body.subject(),
                body.sentAt(),
                body.body(),
                k,
                maxChars,
                body.excludeSampleIds() == null ? List.of() : body.excludeSampleIds(),
                body.minDistance() == null ? 0 : body.minDistance());
        return ResponseEntity.ok(new AnalysisFewShotResponse(embeddings.model(), examples));
    }

    @GetMapping("/samples")
    @Operation(summary = "Search the corpus",
            description = "One free-text field covers the sender, the subject, the email text "
                    + "and the reviewer's notes. Unlike the mailbox search the body is always "
                    + "scanned: here you are looking for examples of a phrase rather than for "
                    + "a message you half remember, and the corpus is small enough to afford it.")
    public ResponseEntity<PageResponse<AnalysisSampleResponse>> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AnalysisLabel label,
            @RequestParam(required = false) AnalysisStatus status,
            @Parameter(description = "MAILBOX (captured from synced mail) or PASTED (added by hand)")
            @RequestParam(required = false) String source,
            @Parameter(description = "When the email arrived — not when it was captured")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime receivedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime receivedTo,
            @PageableDefault(size = 25, sort = "receivedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        SampleFilter filter =
                new SampleFilter(search, label, status, source, receivedFrom, receivedTo);
        return ResponseEntity.ok(analysis.search(filter, pageable));
    }

    @GetMapping("/samples/{id}")
    @Operation(summary = "Open one sample: the email text and the annotation written against it")
    public ResponseEntity<AnalysisSampleDetailResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(analysis.getDetail(id));
    }

    @PatchMapping("/samples/{id}")
    @Operation(summary = "Review a sample",
            description = "Label, status, annotation and notes; a field left out is left "
                    + "alone. The annotation must parse as JSON — an empty string clears it. "
                    + "Marking a sample READY is refused unless it has both a label and an "
                    + "annotation, since READY is what the export reads.")
    public ResponseEntity<AnalysisSampleDetailResponse> update(
            @PathVariable Long id, @RequestBody AnalysisSampleUpdateRequest body) {
        return ResponseEntity.ok(analysis.update(id, body));
    }

    @DeleteMapping("/samples/{id}")
    @Operation(summary = "Drop a sample from the corpus",
            description = "The email itself is untouched — this removes the copy kept for "
                    + "training, not the message in the mailbox. Note that a deleted sample "
                    + "will be captured again by the next run over the same folder; SKIPPED "
                    + "is the status for junk that should stay out.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        analysis.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/capture")
    @Operation(summary = "Take matching circulars into the corpus",
            description = "source=MAILBOX (the default) scopes with the same axes the Mailbox "
                    + "tab filters on; source=WEB takes posts off the open boards the Feed "
                    + "tab collects, which is where the layouts nobody addressed to this desk "
                    + "live. "
                    + "Neither touches what it read — no flag, no move, nothing written back "
                    + "to a board. Everything lands unlabelled and unreviewed, and anything "
                    + "already captured is skipped, so running this again after a sync or a "
                    + "fetch adds only what is new.")
    public ResponseEntity<AnalysisCaptureResponse> capture(
            @RequestBody(required = false) AnalysisCaptureRequest body) {
        AnalysisCaptureRequest req = body != null ? body
                : new AnalysisCaptureRequest(null, null, null, null, null, null, null, null, null);
        return ResponseEntity.ok(analysis.capture(req));
    }

    @PostMapping("/samples")
    @Operation(summary = "Add one email by hand",
            description = "For a machine with no mailbox configured, and for an example that "
                    + "never arrived in this one.")
    public ResponseEntity<AnalysisSampleDetailResponse> paste(
            @Valid @RequestBody AnalysisPasteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(analysis.paste(body));
    }

    /**
     * The training file.
     *
     * <p>Served as an attachment with a stamped filename rather than as JSON in a body: what
     * comes out of here is a file that goes to a training job somewhere else entirely, and
     * the browser saving it under a name that says when it was cut is the difference between
     * a folder of datasets and a folder of {@code export(3).jsonl}.
     */
    @GetMapping(value = "/export", produces = "application/x-ndjson")
    @Operation(summary = "Download the ready samples as JSONL finetuning data",
            description = "One example per line: a system prompt (the same on every line, and "
                    + "the one a real caller would send), the email as the user turn, and the "
                    + "annotation as the assistant turn. Only READY samples are written, in "
                    + "id order, so two exports of the same corpus are the same file.")
    public ResponseEntity<String> exportJsonl() {
        String body = export.toJsonl();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + export.filename() + "\"")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(body);
    }
}
