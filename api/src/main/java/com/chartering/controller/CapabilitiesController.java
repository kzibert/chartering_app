package com.chartering.controller;

import com.chartering.config.AnalysisProperties;
import com.chartering.config.EmbeddingProperties;
import com.chartering.config.FeedProperties;
import com.chartering.config.MailCampaignProperties;
import com.chartering.config.ParserProperties;
import com.chartering.config.VesselLookupProperties;
import com.chartering.dto.CapabilitiesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment offers, in one call the UI makes once.
 *
 * <p>It replaced three status calls the shell made on every load to decide which tabs to draw -
 * one of which counted four tables and asked a model server whether it was up - with a read of
 * the switches themselves. The office and the hosted instance answer differently, which is the
 * point: these are the facts the two do not share.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
@RequiredArgsConstructor
@Tag(name = "Capabilities", description = "Which optional features this deployment has")
public class CapabilitiesController {

    private final AnalysisProperties analysis;
    private final EmbeddingProperties embedding;
    private final ParserProperties parser;
    private final FeedProperties feed;
    private final VesselLookupProperties lookup;
    private final MailCampaignProperties mail;

    @GetMapping
    @Operation(summary = "The optional features this deployment has",
            description = "Configuration only: no database query and no network call. The Intake tab "
                    + "exists for a person where parserLocal is true, or parserCloud is true and their "
                    + "browser holds a key.")
    public ResponseEntity<CapabilitiesResponse> capabilities() {
        return ResponseEntity.ok(new CapabilitiesResponse(
                analysis.isEnabled(),
                embedding.getUrl() != null && !embedding.getUrl().isBlank(),
                parser.isEnabled(),
                parser.isCloudEnabled(),
                feed.isEnabled(),
                lookup.isEnabled(),
                mail.isSmtpBlocked()));
    }
}
