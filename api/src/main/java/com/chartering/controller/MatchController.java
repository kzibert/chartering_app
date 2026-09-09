package com.chartering.controller;

import com.chartering.dto.MatchOutcomeRequest;
import com.chartering.dto.MatchResponse;
import com.chartering.dto.MatchSettingsRequest;
import com.chartering.dto.MatchSettingsResponse;
import com.chartering.dto.MatchSummaryResponse;
import com.chartering.service.MatchService;
import com.chartering.service.MatchSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/matches")
@RequiredArgsConstructor
@Tag(name = "Match", description = "Which ships suit which cargoes, and what was decided about it")
public class MatchController {

    private final MatchService matchService;
    private final MatchSettings settings;

    @GetMapping
    @Operation(summary = "Every live cargo with the tonnage against it counted",
            description = "The Match tab's landing view, ordered by how much unworked tonnage "
                    + "each cargo has - which is where a day's work actually starts. Nothing "
                    + "here is stored: the scores are computed on the request, because a "
                    + "stored one goes stale the moment a position or a cargo moves.")
    public ResponseEntity<List<MatchSummaryResponse>> overview() {
        return ResponseEntity.ok(matchService.overview());
    }

    @GetMapping("/cargo/{cargoId}")
    @Operation(summary = "Tonnage for one cargo, best first",
            description = "Each row carries the checks that produced its score, with the "
                    + "figures in them. A check comes back PASS, FAIL or UNKNOWN, and the "
                    + "third is not the second: half this fleet has no gear recorded, and "
                    + "reading \"not on file\" as \"does not fit\" would rule out most of the "
                    + "tonnage on the desk. A FAIL rules a pairing out; an UNKNOWN only costs "
                    + "it points. includeRuledOut=true also returns the pairs that failed, "
                    + "with the reason - \"why is she not on this list\" is a question with an "
                    + "answer.")
    public ResponseEntity<List<MatchResponse>> forCargo(
            @PathVariable Long cargoId,
            @RequestParam(defaultValue = "false") boolean includeRuledOut,
            @RequestParam(required = false) Integer minScore) {
        return ResponseEntity.ok(matchService.forCargo(cargoId, includeRuledOut, minScore));
    }

    @GetMapping("/position/{positionId}")
    @Operation(summary = "Cargoes for one ship's position, best first",
            description = "The same scorer read the other way round. Most of the mail on this "
                    + "desk is somebody else's tonnage asking for work - \"pls propose "
                    + "suitable cgoes for our below home tonnages\" - and answering it is "
                    + "exactly this query.")
    public ResponseEntity<List<MatchResponse>> forPosition(
            @PathVariable Long positionId,
            @RequestParam(defaultValue = "false") boolean includeRuledOut,
            @RequestParam(required = false) Integer minScore) {
        return ResponseEntity.ok(matchService.forPosition(positionId, includeRuledOut, minScore));
    }

    @PutMapping("/cargo/{cargoId}/vessel/{vesselId}")
    @Operation(summary = "Record what was done about a pairing",
            description = "SHORTLISTED, OFFERED, DECLINED, FIXED or DISMISSED. One row per "
                    + "pairing, replaced rather than appended - offering a ship twice is a "
                    + "correction to the first answer, not a second one. DISMISSED is the "
                    + "one that matters: it is how a ship stops being suggested every "
                    + "morning for a cargo she was already turned down for.")
    public ResponseEntity<MatchResponse> decide(
            @PathVariable Long cargoId,
            @PathVariable Long vesselId,
            @Valid @RequestBody MatchOutcomeRequest req,
            Authentication auth) {
        String by = auth == null ? null : auth.getName();
        return ResponseEntity.ok(matchService.decide(cargoId, vesselId, req, by));
    }

    @DeleteMapping("/cargo/{cargoId}/vessel/{vesselId}")
    @Operation(summary = "Forget what was decided about a pairing",
            description = "The pair goes back to being an ordinary suggestion.")
    public ResponseEntity<Void> clear(@PathVariable Long cargoId, @PathVariable Long vesselId) {
        matchService.clearDecision(cargoId, vesselId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ settings

    @GetMapping("/settings")
    @Operation(summary = "The four numbers the matching rule cannot derive")
    public ResponseEntity<MatchSettingsResponse> settings() {
        return ResponseEntity.ok(toResponse(settings.values()));
    }

    @PutMapping("/settings")
    @Operation(summary = "Change the ballast speed or what counts as filling a ship",
            description = "The utilisation floor is what stops a 14,000-tonner being offered "
                    + "for a 4,000-tonne parcel: she can lift it, which is the problem, and "
                    + "freight is earned by the tonne while the ship is paid for whole. Raise "
                    + "it and part cargoes stop being suggested; lower it and they come back. "
                    + "Every field is optional, so the form can send one without holding the "
                    + "others.")
    public ResponseEntity<MatchSettingsResponse> updateSettings(
            @Valid @RequestBody MatchSettingsRequest req) {
        return ResponseEntity.ok(toResponse(settings.update(
                req.getBallastSpeedKnots(), req.getPortAllowanceHours(),
                req.getMinUtilisationPercent(), req.getIdealUtilisationPercent())));
    }

    @DeleteMapping("/settings")
    @Operation(summary = "Back to the defaults")
    public ResponseEntity<MatchSettingsResponse> resetSettings() {
        return ResponseEntity.ok(toResponse(settings.reset()));
    }

    private static MatchSettingsResponse toResponse(MatchSettings.Values v) {
        MatchSettings.Values defaults = MatchSettings.defaults();
        return new MatchSettingsResponse(
                v.ballastSpeedKnots(), v.portAllowanceHours(),
                v.minUtilisationPercent(), v.idealUtilisationPercent(),
                defaults.ballastSpeedKnots(), defaults.portAllowanceHours(),
                defaults.minUtilisationPercent(), defaults.idealUtilisationPercent());
    }
}
