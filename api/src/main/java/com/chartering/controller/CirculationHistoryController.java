package com.chartering.controller;

import com.chartering.dto.CirculationMessageResponse;
import com.chartering.dto.CirculationRunDetailResponse;
import com.chartering.dto.CirculationRunResponse;
import com.chartering.dto.PageResponse;
import com.chartering.service.CirculationHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Circulation history — every run that was started, who it touched, and what each of them
 * received. Backs the History dropdown on the Circulars tab.
 */
@RestController
@RequestMapping("/api/v1/circulations")
@RequiredArgsConstructor
@Tag(name = "Circulation history", description = "Past circulations and the messages they sent")
public class CirculationHistoryController {

    private final CirculationHistoryService history;

    @GetMapping
    @Operation(summary = "Past circulations, newest first",
            description = "One line per run. Recipients and the circular itself are omitted — "
                    + "open a run to get them.")
    public ResponseEntity<PageResponse<CirculationRunResponse>> history(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(history.history(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One circulation with every address it touched",
            description = "Includes addresses that were skipped as duplicates or as not-working, "
                    + "and those left PENDING by a cancelled run. composedHtml is the circular "
                    + "before the mail merge — ask for a recipient's message to see it merged.")
    public ResponseEntity<CirculationRunDetailResponse> detail(@PathVariable Long id) {
        return ResponseEntity.ok(history.detail(id));
    }

    @GetMapping("/{id}/recipients/{recipientId}/message")
    @Operation(summary = "The exact message one recipient received",
            description = "Reproduced by replaying the run's stored circular through the same "
                    + "mail merge with that recipient's stored fields. Returns the HTML part and "
                    + "the plain-text alternative, which is what was actually sent.")
    public ResponseEntity<CirculationMessageResponse> message(@PathVariable Long id,
                                                              @PathVariable Long recipientId) {
        return ResponseEntity.ok(history.message(id, recipientId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a circulation from the history")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        history.delete(id);
        return ResponseEntity.noContent().build();
    }
}
