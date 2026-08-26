package com.chartering.controller;

import com.chartering.dto.PageResponse;
import com.chartering.dto.VesselPositionRequest;
import com.chartering.dto.VesselPositionResponse;
import com.chartering.model.PositionStatus;
import com.chartering.service.VesselPositionService;
import com.chartering.service.VesselPositionService.PositionFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/positions")
@RequiredArgsConstructor
@Tag(name = "Open fleet", description = "Opening positions — one row per report, not one per vessel")
public class VesselPositionController {

    private final VesselPositionService positionService;

    @GetMapping
    @Operation(summary = "Search opening positions",
            description = "current=true (the default) returns the newest LIVE row per vessel, "
                    + "which is what \"open fleet\" means; current=false searches every "
                    + "position ever reported, which is where superseded and fixed ones live. "
                    + "status only applies with current=false - alongside current=true it "
                    + "would contradict it rather than narrow it, so it is ignored. "
                    + "vesselName matches the current name or any former one. The open-date "
                    + "window matches positions OVERLAPPING it and always returns positions "
                    + "with no dates on file, because \"SPOT\" names no day and is the "
                    + "promptest tonnage on the list. minSize/maxSize read DWCC where it is "
                    + "recorded and DWT where it is not.")
    public ResponseEntity<PageResponse<VesselPositionResponse>> search(
            @RequestParam(required = false) String vesselName,
            @RequestParam(required = false) Long vesselId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) Long openAreaId,
            @RequestParam(required = false) LocalDate openFrom,
            @RequestParam(required = false) LocalDate openTo,
            @RequestParam(required = false) Long reportedByCompanyId,
            @RequestParam(required = false) Integer reportedWithinDays,
            @RequestParam(required = false) BigDecimal minSize,
            @RequestParam(required = false) BigDecimal maxSize,
            @RequestParam(required = false) Boolean geared,
            @RequestParam(defaultValue = "false") boolean includeBanned,
            @RequestParam(defaultValue = "true") boolean current,
            @PageableDefault(size = 25, sort = "reportedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        List<PositionStatus> statuses = status == null ? null
                : status.stream().map(VesselPositionService::parseStatus).toList();
        PositionFilter filter = new PositionFilter(vesselName, vesselId, statuses, openAreaId,
                openFrom, openTo, reportedByCompanyId, reportedWithinDays,
                minSize, maxSize, geared, includeBanned, current);
        return ResponseEntity.ok(positionService.search(filter, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one position")
    public ResponseEntity<VesselPositionResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(positionService.get(id));
    }

    @GetMapping("/vessel/{vesselId}")
    @Operation(summary = "Every position ever reported about one vessel, newest first",
            description = "Nothing here is deleted when it is replaced, so this is the "
                    + "record of where she has been offered over time - which is worth "
                    + "reading when a cargo lands in a range she keeps turning up in.")
    public ResponseEntity<List<VesselPositionResponse>> history(@PathVariable Long vesselId) {
        return ResponseEntity.ok(positionService.history(vesselId));
    }

    @PostMapping
    @Operation(summary = "Record a position",
            description = "Only the vessel is required: \"MV LADY LEYLA SPOT AT MARMARA\" is a "
                    + "complete position as far as the market is concerned. A new LIVE report "
                    + "supersedes the same reporter's previous live one for that vessel and "
                    + "nobody else's - two brokers disagreeing is two facts, one broker "
                    + "repeating themselves is one.")
    public ResponseEntity<VesselPositionResponse> create(
            @Valid @RequestBody VesselPositionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(positionService.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a position", description = "Replaces the whole record.")
    public ResponseEntity<VesselPositionResponse> update(
            @PathVariable Long id, @Valid @RequestBody VesselPositionRequest req) {
        return ResponseEntity.ok(positionService.update(id, req));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Move a position out of the live set — she fixed, or it was pulled")
    public ResponseEntity<VesselPositionResponse> setStatus(
            @PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(
                positionService.setStatus(id, VesselPositionService.parseStatus(status)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a position",
            description = "Rarely the right move: a report that turned out to be wrong is "
                    + "history worth keeping, and WITHDRAWN says so without losing it. This "
                    + "is for a row entered by mistake.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        positionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
