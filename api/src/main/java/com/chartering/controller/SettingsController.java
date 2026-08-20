package com.chartering.controller;

import com.chartering.config.BrevoProperties;
import com.chartering.dto.CirculationProviderRequest;
import com.chartering.dto.CirculationSettingsRequest;
import com.chartering.dto.CirculationSettingsResponse;
import com.chartering.service.SettingsService;
import com.chartering.service.SettingsService.CirculationSettings;
import com.chartering.service.mail.CircularProvider;
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
    private final BrevoProperties brevo;

    @GetMapping("/circulation")
    @Operation(summary = "Circulation settings in force, plus the defaults they reset to",
            description = "Credentials are not included and cannot be set here — MAIL_USERNAME, "
                    + "MAIL_PASSWORD and BREVO_API_KEY stay in the environment.")
    public ResponseEntity<CirculationSettingsResponse> circulation() {
        return ResponseEntity.ok(toResponse(settings.circulation()));
    }

    @PutMapping("/circulation")
    @Operation(summary = "Change the circulation settings",
            description = "Takes effect on the next campaign started; a run already in "
                    + "flight keeps the pacing it began with. Pacing is saved against the "
                    + "provider in force, so each flow keeps its own tuning.")
    public ResponseEntity<CirculationSettingsResponse> update(
            @Valid @RequestBody CirculationSettingsRequest req) {
        return ResponseEntity.ok(toResponse(settings.updateCirculation(req)));
    }

    @PutMapping("/circulation/provider")
    @Operation(summary = "Choose how circulars are sent: the mailbox over SMTP, or the Brevo API",
            description = "Applies from the next campaign started. The pacing shown afterwards "
                    + "is the chosen provider's own — it has its own stored values and its own "
                    + "defaults, because seconds between messages protects a personal mailbox "
                    + "and merely wastes time through an ESP.")
    public ResponseEntity<CirculationSettingsResponse> setProvider(
            @Valid @RequestBody CirculationProviderRequest req) {
        CircularProvider provider = req.isUseBrevo() ? CircularProvider.BREVO : CircularProvider.SMTP;
        return ResponseEntity.ok(toResponse(settings.updateProvider(provider)));
    }

    @DeleteMapping("/circulation")
    @Operation(summary = "Reset the circulation settings to the configured defaults",
            description = "Scoped to the provider in force; the choice of provider itself is kept.")
    public ResponseEntity<CirculationSettingsResponse> reset() {
        return ResponseEntity.ok(toResponse(settings.resetCirculation()));
    }

    private CirculationSettingsResponse toResponse(CirculationSettings s) {
        CirculationSettings d = settings.circulationDefaults(s.provider());
        String key = brevo.getApiKey();
        return new CirculationSettingsResponse(
                s.provider().name(), s.provider().label(), key != null && !key.isBlank(),
                s.fromAddress(), s.fromName(), s.smtpHost(), s.smtpPort(),
                s.minDelayMs(), s.maxDelayMs(), s.maxRecipientsPerCampaign(), s.batchPauseMs(),
                !s.equals(d),
                CirculationSettingsResponse.defaultsOnly(d.provider().name(), d.provider().label(),
                        d.fromAddress(), d.fromName(), d.smtpHost(), d.smtpPort(), d.minDelayMs(),
                        d.maxDelayMs(), d.maxRecipientsPerCampaign(), d.batchPauseMs()));
    }
}
