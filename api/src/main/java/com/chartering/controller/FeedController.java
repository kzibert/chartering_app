package com.chartering.controller;

import com.chartering.dto.FeedItemResponse;
import com.chartering.dto.FeedParserResponse;
import com.chartering.dto.FeedSettingsRequest;
import com.chartering.dto.FeedSettingsResponse;
import com.chartering.dto.FeedSourceRequest;
import com.chartering.dto.FeedSourceResponse;
import com.chartering.dto.FeedStatusResponse;
import com.chartering.dto.FeedSummaryResponse;
import com.chartering.dto.FeedTopicRequest;
import com.chartering.dto.FeedTopicResponse;
import com.chartering.dto.FeedTopicSelectionRequest;
import com.chartering.dto.PageResponse;
import com.chartering.service.feed.FeedFetchService;
import com.chartering.service.feed.FeedPrompts;
import com.chartering.service.feed.FeedQueryService;
import com.chartering.service.feed.FeedSettings;
import com.chartering.service.feed.FeedSourceService;
import com.chartering.service.feed.FeedSummaryService;
import com.chartering.service.feed.FeedTopicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Feed: outside sources, topics, and the local model's summaries of what the sources say.
 *
 * <p><b>Visible everywhere, analysis local.</b> Unlike Intake and Analysis this tab is part of
 * every deployment: sources, topics, the prompt and the summaries are rows in the shared database,
 * so the hosted instance reads and edits them. Fetching, summarising and asking the model its
 * window are what {@code FEED_ANALYSIS_ENABLED} switches, and only those answer 404 when it is off.
 */
@RestController
@RequestMapping("/api/v1/feed")
@RequiredArgsConstructor
@Tag(name = "Feed", description = "Outside sources summarised by the local model (summaries readable everywhere)")
public class FeedController {

    private final FeedQueryService queries;
    private final FeedSourceService sourceService;
    private final FeedTopicService topicService;
    private final FeedFetchService fetch;
    private final FeedSummaryService summary;
    private final FeedSettings settings;

    // ------------------------------------------------------------------ status

    @GetMapping("/status")
    @Operation(summary = "Counts, what is running, and whether this deployment can fetch and summarise")
    public ResponseEntity<FeedStatusResponse> status() {
        return ResponseEntity.ok(queries.status());
    }

    // ------------------------------------------------------------------ sources

    @GetMapping("/sources")
    @Operation(summary = "Every source, with its last fetch and how many items it has")
    public ResponseEntity<List<FeedSourceResponse>> sources() {
        return ResponseEntity.ok(sourceService.list());
    }

    @PostMapping("/sources")
    @Operation(summary = "Add a source",
            description = "TELEGRAM takes @handle or a t.me link to a public channel; RSS an RSS or Atom feed "
                    + "address; WEBSITE an address and the parserKey of a site parser (GET /parsers).")
    public ResponseEntity<FeedSourceResponse> createSource(@Valid @RequestBody FeedSourceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sourceService.create(req));
    }

    @PutMapping("/sources/{id}")
    @Operation(summary = "Change a source")
    public ResponseEntity<FeedSourceResponse> updateSource(@PathVariable Long id,
                                                           @Valid @RequestBody FeedSourceRequest req) {
        return ResponseEntity.ok(sourceService.update(id, req));
    }

    @DeleteMapping("/sources/{id}")
    @Operation(summary = "Remove a source and the items it collected",
            description = "Summaries already written stay; they lose their links to this source's items. "
                    + "Disable a source instead to stop it without losing anything.")
    public ResponseEntity<Void> deleteSource(@PathVariable Long id) {
        sourceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/parsers")
    @Operation(summary = "The site parsers a WEBSITE source can use")
    public ResponseEntity<List<FeedParserResponse>> parsers() {
        return ResponseEntity.ok(queries.parsers());
    }

    // ------------------------------------------------------------------ fetching (local)

    @PostMapping("/fetch")
    @Operation(summary = "Fetch every enabled source now",
            description = "Returns at once; watch GET /status for fetchRunning. 404 where FEED_ANALYSIS_ENABLED is off.")
    public ResponseEntity<FeedStatusResponse> fetchAll() {
        fetch.requestFetch();
        return ResponseEntity.accepted().body(queries.status());
    }

    @PostMapping("/sources/{id}/fetch")
    @Operation(summary = "Fetch one source now, enabled or not")
    public ResponseEntity<FeedStatusResponse> fetchOne(@PathVariable Long id) {
        fetch.requestFetch(id);
        return ResponseEntity.accepted().body(queries.status());
    }

    // ------------------------------------------------------------------ items

    @GetMapping("/items")
    @Operation(summary = "Collected items, newest first")
    public ResponseEntity<PageResponse<FeedItemResponse>> items(
            @RequestParam(required = false) Long sourceId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(queries.items(sourceId, q, from, to, page, size));
    }

    @GetMapping("/items/{id}")
    @Operation(summary = "One collected item, whole",
            description = "The post as the source published it. Read by the Intake screens "
                    + "as well as this tab: a cargo or a position read off a board is checked "
                    + "by eye against the post, the way a mailed one is checked against the "
                    + "email. Not behind the analysis switch, for the reason nothing else "
                    + "here is — what a source collected is data, and reading it costs "
                    + "nobody's server anything.")
    public ResponseEntity<FeedItemResponse> item(@PathVariable Long id) {
        return ResponseEntity.ok(queries.item(id));
    }

    // ------------------------------------------------------------------ topics

    @GetMapping("/topics")
    @Operation(summary = "The topics, in their order")
    public ResponseEntity<List<FeedTopicResponse>> topics() {
        return ResponseEntity.ok(topicService.list());
    }

    @PostMapping("/topics")
    @Operation(summary = "Add a topic")
    public ResponseEntity<FeedTopicResponse> createTopic(@Valid @RequestBody FeedTopicRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(topicService.create(req));
    }

    @PutMapping("/topics/{id}")
    @Operation(summary = "Change a topic")
    public ResponseEntity<FeedTopicResponse> updateTopic(@PathVariable Long id, @Valid @RequestBody FeedTopicRequest req) {
        return ResponseEntity.ok(topicService.update(id, req));
    }

    @DeleteMapping("/topics/{id}")
    @Operation(summary = "Remove a topic", description = "Its summaries stay, under the name they were written with.")
    public ResponseEntity<Void> deleteTopic(@PathVariable Long id) {
        topicService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/topics/selection")
    @Operation(summary = "Select exactly these topics for the next Summarise")
    public ResponseEntity<List<FeedTopicResponse>> selectTopics(@Valid @RequestBody FeedTopicSelectionRequest req) {
        return ResponseEntity.ok(topicService.select(req.getSelectedIds()));
    }

    // ------------------------------------------------------------------ summaries

    @GetMapping("/summaries")
    @Operation(summary = "Summaries, newest first, optionally for one topic")
    public ResponseEntity<PageResponse<FeedSummaryResponse>> summaries(
            @RequestParam(required = false) Long topicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(queries.summaries(topicId, page, size));
    }

    @GetMapping("/summaries/{id}")
    @Operation(summary = "One summary with the items it was written from")
    public ResponseEntity<FeedSummaryResponse> summary(@PathVariable Long id) {
        return ResponseEntity.ok(queries.summary(id));
    }

    @PostMapping("/summaries/run")
    @Operation(summary = "Summarise the selected topics now",
            description = "Returns at once; watch GET /status for summaryRunning and the stage. 409 while the "
                    + "email parser is reading on the same model; 404 where FEED_ANALYSIS_ENABLED is off.")
    public ResponseEntity<FeedStatusResponse> run() {
        summary.requestRun();
        return ResponseEntity.accepted().body(queries.status());
    }

    // ------------------------------------------------------------------ settings

    @GetMapping("/settings")
    @Operation(summary = "The context window, answer sizes, lookback, call cap, fetch interval and prompts")
    public ResponseEntity<FeedSettingsResponse> settings() {
        return ResponseEntity.ok(toResponse(settings.values()));
    }

    @PutMapping("/settings")
    @Operation(summary = "Change any of the settings", description = "Every field optional. A blank prompt restores its default.")
    public ResponseEntity<FeedSettingsResponse> updateSettings(@RequestBody FeedSettingsRequest req) {
        return ResponseEntity.ok(toResponse(settings.update(new FeedSettings.Update(
                req.getContextWindowTokens(), req.getSummaryMaxTokens(), req.getNotesMaxTokens(),
                req.getLookbackDays(), req.getMaxCallsPerTopic(), req.getFetchIntervalMinutes(),
                req.getSystemPrompt(), req.getNotesPrompt()))));
    }

    @DeleteMapping("/settings")
    @Operation(summary = "The numeric settings back to their defaults; the prompts are kept")
    public ResponseEntity<FeedSettingsResponse> resetSettings() {
        return ResponseEntity.ok(toResponse(settings.resetNumbers()));
    }

    @DeleteMapping("/settings/prompts")
    @Operation(summary = "Both prompts back to their defaults")
    public ResponseEntity<FeedSettingsResponse> resetPrompts() {
        return ResponseEntity.ok(toResponse(settings.resetPrompts()));
    }

    @GetMapping("/settings/detect-context")
    @Operation(summary = "Ask the model server how large its context window is (local only)")
    public ResponseEntity<Map<String, Integer>> detectContext() {
        return ResponseEntity.ok(Map.of("contextWindowTokens", queries.detectContextWindow()));
    }

    private static FeedSettingsResponse toResponse(FeedSettings.Values v) {
        FeedSettings.Values d = FeedSettings.defaults();
        return new FeedSettingsResponse(v.contextWindowTokens(), v.summaryMaxTokens(), v.notesMaxTokens(),
                v.lookbackDays(), v.maxCallsPerTopic(), v.fetchIntervalMinutes(), v.systemPrompt(), v.notesPrompt(),
                v.systemPromptCustomised(), v.notesPromptCustomised(),
                d.contextWindowTokens(), d.summaryMaxTokens(), d.notesMaxTokens(), d.lookbackDays(),
                d.maxCallsPerTopic(), d.fetchIntervalMinutes(), FeedPrompts.DEFAULT_SYSTEM, FeedPrompts.DEFAULT_NOTES,
                FeedPrompts.PLACEHOLDERS);
    }
}
