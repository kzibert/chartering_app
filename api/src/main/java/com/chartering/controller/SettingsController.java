package com.chartering.controller;

import com.chartering.dto.CirculationSettingsRequest;
import com.chartering.dto.CirculationSettingsResponse;
import com.chartering.service.SettingsService;
import com.chartering.service.SettingsService.CirculationSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Runtime settings. Values live in the database and override the configured defaults from
 * application.yml; deleting them restores those defaults.
 */
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
@Validated
@Tag(name = "Settings", description = "Runtime settings, stored in the database")
public class SettingsController {

    private final SettingsService settings;

    @GetMapping("/circulation")
    @Operation(summary = "Circulation settings in force, plus the defaults they reset to",
            description = "SMTP credentials are not included and cannot be set here — "
                    + "MAIL_USERNAME and MAIL_PASSWORD stay in the environment.")
    public ResponseEntity<CirculationSettingsResponse> circulation() {
        return ResponseEntity.ok(toResponse(settings.circulation()));
    }

    @PutMapping("/circulation")
    @Operation(summary = "Change the circulation settings",
            description = "Takes effect on the next campaign started; a run already in "
                    + "flight keeps the pacing it began with.")
    public ResponseEntity<CirculationSettingsResponse> update(
            @Valid @RequestBody CirculationSettingsRequest req) {
        return ResponseEntity.ok(toResponse(settings.updateCirculation(req)));
    }

    @DeleteMapping("/circulation")
    @Operation(summary = "Reset the circulation settings to the configured defaults")
    public ResponseEntity<CirculationSettingsResponse> reset() {
        return ResponseEntity.ok(toResponse(settings.resetCirculation()));
    }

    private CirculationSettingsResponse toResponse(CirculationSettings s) {
        CirculationSettings d = settings.circulationDefaults();
        return new CirculationSettingsResponse(
                s.fromAddress(), s.fromName(), s.smtpHost(), s.smtpPort(),
                s.minDelayMs(), s.maxDelayMs(), s.maxRecipientsPerCampaign(), s.batchPauseMs(),
                !s.equals(d),
                CirculationSettingsResponse.defaultsOnly(d.fromAddress(), d.fromName(),
                        d.smtpHost(), d.smtpPort(), d.minDelayMs(), d.maxDelayMs(),
                        d.maxRecipientsPerCampaign(), d.batchPauseMs()));
    }
}
