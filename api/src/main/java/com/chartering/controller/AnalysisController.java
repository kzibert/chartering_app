package com.chartering.controller;

import com.chartering.dto.AnalysisCaptureRequest;
import com.chartering.dto.AnalysisCaptureResponse;
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

    @GetMapping("/status")
    @Operation(summary = "Whether this deployment runs the analysis workbench, and how the corpus stands",
            description = "The one endpoint here that answers when the feature is off — it "
                    + "returns enabled=false and nothing else, which is what tells the UI to "
                    + "leave the tab out of the navigation rather than show one that errors "
                    + "when clicked.")
    public ResponseEntity<AnalysisStatusResponse> status() {
        return ResponseEntity.ok(analysis.status());
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
    @Operation(summary = "Take matching synced mail into the corpus",
            description = "Scoped with the same axes the Mailbox tab filters on. Nothing in "
                    + "the mailbox is changed — no flag, no move. Everything lands unlabelled "
                    + "and unreviewed, and mail already captured is skipped, so running this "
                    + "again after a sync adds only what is new.")
    public ResponseEntity<AnalysisCaptureResponse> capture(
            @RequestBody(required = false) AnalysisCaptureRequest body) {
        AnalysisCaptureRequest req = body != null ? body
                : new AnalysisCaptureRequest(null, null, null, null, null, null, null);
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
