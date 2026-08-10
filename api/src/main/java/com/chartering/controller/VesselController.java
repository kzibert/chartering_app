package com.chartering.controller;

import com.chartering.dto.*;
import com.chartering.service.VesselService;
import com.chartering.service.VesselService.VesselFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/vessels")
@RequiredArgsConstructor
@Tag(name = "Vessels", description = "Filter vessels and manage their confirmation status")
public class VesselController {

    private final VesselService vesselService;

    @GetMapping
    @Operation(summary = "Search vessels",
            description = "All filters optional; numeric filters are min/max ranges. "
                    + "vesselType and flag accept repeated values (?vesselType=A&vesselType=B).")
    public ResponseEntity<PageResponse<VesselResponse>> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String imoNumber,
            @RequestParam(required = false) BigDecimal minDwt,
            @RequestParam(required = false) BigDecimal maxDwt,
            @RequestParam(required = false) BigDecimal minDwcc,
            @RequestParam(required = false) BigDecimal maxDwcc,
            @RequestParam(required = false) BigDecimal minGrain,
            @RequestParam(required = false) BigDecimal maxGrain,
            @RequestParam(required = false) BigDecimal minBale,
            @RequestParam(required = false) BigDecimal maxBale,
            @RequestParam(required = false) BigDecimal minDraft,
            @RequestParam(required = false) BigDecimal maxDraft,
            @RequestParam(required = false) Integer minYear,
            @RequestParam(required = false) Integer maxYear,
            @RequestParam(required = false) List<String> vesselType,
            @RequestParam(required = false) List<String> flag,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String ownerName,
            @RequestParam(required = false) Boolean confirmed,
            @RequestParam(defaultValue = "false") boolean includeBanned,
            @RequestParam(required = false) Boolean legacy,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {

        VesselFilter filter = new VesselFilter(name, imoNumber, minDwt, maxDwt, minDwcc, maxDwcc,
                minGrain, maxGrain, minBale, maxBale, minDraft, maxDraft, minYear, maxYear,
                vesselType, flag, ownerId, ownerName, confirmed, includeBanned, legacy);
        return ResponseEntity.ok(vesselService.search(filter, pageable));
    }

    @GetMapping("/contacts")
    @Operation(summary = "Owner-company email contacts for all vessels matching the filter",
            description = "Same filters as the vessel search (pagination ignored — operates on the "
                    + "whole filtered set). Returns the email contacts of the owner companies of "
                    + "every matching vessel; confirmedOnly=true restricts to confirmed emails. "
                    + "mainOnly=true collapses each owner to a single address — the one flagged as "
                    + "the company's main email, or its first email when none is flagged. "
                    + "Powers the Vessels-tab bulk add-to-email-list actions.")
    public ResponseEntity<List<ContactResponse>> ownerEmailContacts(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String imoNumber,
            @RequestParam(required = false) BigDecimal minDwt,
            @RequestParam(required = false) BigDecimal maxDwt,
            @RequestParam(required = false) BigDecimal minDwcc,
            @RequestParam(required = false) BigDecimal maxDwcc,
            @RequestParam(required = false) BigDecimal minGrain,
            @RequestParam(required = false) BigDecimal maxGrain,
            @RequestParam(required = false) BigDecimal minBale,
            @RequestParam(required = false) BigDecimal maxBale,
            @RequestParam(required = false) BigDecimal minDraft,
            @RequestParam(required = false) BigDecimal maxDraft,
            @RequestParam(required = false) Integer minYear,
            @RequestParam(required = false) Integer maxYear,
            @RequestParam(required = false) List<String> vesselType,
            @RequestParam(required = false) List<String> flag,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String ownerName,
            @RequestParam(required = false) Boolean confirmed,
            @RequestParam(defaultValue = "false") boolean includeBanned,
            @RequestParam(required = false) Boolean legacy,
            @RequestParam(defaultValue = "false") boolean confirmedOnly,
            @RequestParam(defaultValue = "false") boolean mainOnly) {

        VesselFilter filter = new VesselFilter(name, imoNumber, minDwt, maxDwt, minDwcc, maxDwcc,
                minGrain, maxGrain, minBale, maxBale, minDraft, maxDraft, minYear, maxYear,
                vesselType, flag, ownerId, ownerName, confirmed, includeBanned, legacy);
        return ResponseEntity.ok(mainOnly
                ? vesselService.ownerMainEmailContacts(filter, confirmedOnly)
                : vesselService.ownerEmailContacts(filter, confirmedOnly));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a vessel with its owner company and that company's contacts")
    public ResponseEntity<VesselDetailResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(vesselService.getDetail(id));
    }

    @PostMapping
    @Operation(summary = "Create a vessel")
    public ResponseEntity<VesselResponse> create(@Valid @RequestBody VesselRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(vesselService.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a vessel")
    public ResponseEntity<VesselResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody VesselRequest req) {
        return ResponseEntity.ok(vesselService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a vessel")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        vesselService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/confirm")
    @Operation(summary = "Mark a vessel reached & confirmed up to date (or clear with ?confirmed=false)")
    public ResponseEntity<VesselResponse> confirm(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean confirmed,
            @RequestBody(required = false) ConfirmRequest req) {
        return ResponseEntity.ok(vesselService.setConfirmed(id, confirmed, req));
    }

    @PatchMapping("/{id}/ban")
    @Operation(summary = "Ban a vessel as Russian-rooted (or unban with ?banned=false)")
    public ResponseEntity<VesselResponse> ban(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean banned) {
        return ResponseEntity.ok(vesselService.setBanned(id, banned));
    }
}
