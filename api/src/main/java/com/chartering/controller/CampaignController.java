package com.chartering.controller;

import com.chartering.dto.CampaignConfigResponse;
import com.chartering.dto.CampaignRequest;
import com.chartering.dto.CampaignStatusResponse;
import com.chartering.dto.CirculationRunResponse;
import com.chartering.service.EmailCampaignService;
import com.chartering.service.MailTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Circulars: one campaign at a time, sent individually to each recipient.
 *
 * <p>Starting a campaign returns immediately with 202 — a paced run takes minutes, so the
 * UI polls {@code /status} and tails {@code /log} rather than holding a request open.
 *
 * <p>A run can be paused and picked up later, and a past one can be sent again. Both are
 * keyed by the circulation-history run id rather than by anything held in memory, which is
 * why they still work after the API has been restarted.
 */
@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
@Validated
@Tag(name = "Campaigns", description = "Send circular emails individually to a list of recipients")
public class CampaignController {

    private final EmailCampaignService campaignService;

    @GetMapping("/config")
    @Operation(summary = "Mail settings in force (never includes the password) and whether sending is ready")
    public ResponseEntity<CampaignConfigResponse> config() {
        return ResponseEntity.ok(campaignService.config());
    }

    @GetMapping("/placeholders")
    @Operation(summary = "Mail-merge placeholders available in the subject and body")
    public ResponseEntity<Map<String, String>> placeholders() {
        return ResponseEntity.ok(MailTemplateService.PLACEHOLDERS);
    }

    @PostMapping
    @Operation(summary = "Start a campaign; returns at once while sending continues in the background")
    public ResponseEntity<CampaignStatusResponse> start(@Valid @RequestBody CampaignRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(campaignService.start(req));
    }

    @GetMapping("/current")
    @Operation(summary = "Progress of the running (or most recent) campaign")
    public ResponseEntity<CampaignStatusResponse> status() {
        return ResponseEntity.ok(campaignService.status());
    }

    @PostMapping("/current/cancel")
    @Operation(summary = "Stop the running campaign after the message currently in flight, and close it",
            description = "The addresses it never reached stay recorded as PENDING, so a cancel "
                    + "decided too hastily can still be resumed.")
    public ResponseEntity<CampaignStatusResponse> cancel() {
        return ResponseEntity.ok(campaignService.cancel());
    }

    @PostMapping("/current/pause")
    @Operation(summary = "Stop the running campaign after the message currently in flight, and keep it open",
            description = "The run stays resumable and survives an API restart — the addresses "
                    + "still to reach are held in the circulation history, not in memory.")
    public ResponseEntity<CampaignStatusResponse> pause() {
        return ResponseEntity.ok(campaignService.pause());
    }

    @GetMapping("/resumable")
    @Operation(summary = "Circulations that stopped with somebody still to reach",
            description = "Newest first. Covers runs paused by hand, cancelled, aborted on "
                    + "errors, and those cut off by an API restart.")
    public ResponseEntity<List<CirculationRunResponse>> resumable() {
        return ResponseEntity.ok(campaignService.resumable());
    }

    @PostMapping("/runs/{runId}/resume")
    @Operation(summary = "Carry a stopped circulation on from where it stopped",
            description = "Sends only to the addresses that run never reached, and records the "
                    + "outcome against the same history entry — one circular sent over two "
                    + "sittings stays one circulation.")
    public ResponseEntity<CampaignStatusResponse> resume(@PathVariable Long runId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(campaignService.resume(runId));
    }

    @PostMapping("/runs/{runId}/restart")
    @Operation(summary = "Send a past circulation again, from the top",
            description = "Opens a new history entry rather than rewriting the old one, and "
                    + "replays the circular exactly as that run stored it. Addresses flagged "
                    + "not-working since are dropped, as on any other send.")
    public ResponseEntity<CampaignStatusResponse> restart(@PathVariable Long runId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(campaignService.restart(runId));
    }

    @GetMapping(value = "/current/log", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "The campaign log file as plain text")
    public ResponseEntity<String> log() {
        return ResponseEntity.ok(campaignService.logContents());
    }

    // Deliberately not @Valid: a test send is the thing you do *before* the recipient list
    // exists, so the body's @NotEmpty recipients rule must not apply here. The service
    // checks the subject and body itself.
    @PostMapping("/test")
    @Operation(summary = "Send one test copy to a single address without starting a campaign")
    public ResponseEntity<Void> test(@RequestParam @NotBlank @Email String to,
                                     @RequestBody CampaignRequest req) {
        campaignService.sendTest(to, req);
        return ResponseEntity.noContent().build();
    }
}
