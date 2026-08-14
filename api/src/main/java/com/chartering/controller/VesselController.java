package com.chartering.controller;

import com.chartering.dto.*;
import com.chartering.service.VesselService;
import com.chartering.service.VesselService.VesselFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
            description = "All filters optional. DWT and DWCC are OR'd with each other, and so "
                    + "are grain and bale, because the two figures in each pair are rarely both "
                    + "on file — filling both boxes means \"either\". The pairs are AND'd with "
                    + "each other and with the rest. A size filter only matches vessels where "
                    + "that figure is recorded (0 means unknown, not zero tonnes). maxDraft has "
                    + "no minimum counterpart; yearFrom matches that build year and younger. "
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
            @RequestParam(required = false) BigDecimal maxDraft,
            @RequestParam(required = false) Integer yearFrom,
            @RequestParam(required = false) List<String> vesselType,
            @RequestParam(required = false) List<String> flag,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) Boolean confirmed,
            @RequestParam(defaultValue = "false") boolean includeBanned,
            @RequestParam(required = false) Boolean legacy,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {

        VesselFilter filter = new VesselFilter(name, imoNumber, minDwt, maxDwt, minDwcc, maxDwcc,
                minGrain, maxGrain, minBale, maxBale, maxDraft, yearFrom,
                vesselType, flag, companyId, companyName, confirmed, includeBanned, legacy);
        return ResponseEntity.ok(vesselService.search(filter, pageable));
    }

    @GetMapping("/contacts")
    @Operation(summary = "Owner-company addresses to circulate to, for a set of vessels",
            description = "Same filters as the vessel search (pagination ignored — operates on the "
                    + "whole filtered set), or pass vesselId to use an explicit selection instead, "
                    + "which then wins over the filter. Which of an owner's addresses come back is "
                    + "decided by the circ/main flags: circ-flagged ones if the person has any, "
                    + "else their main one, else all their working ones. confirmedOnly=true "
                    + "restricts to confirmed addresses. Powers the Vessels-tab bulk add.")
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
            @RequestParam(required = false) BigDecimal maxDraft,
            @RequestParam(required = false) Integer yearFrom,
            @RequestParam(required = false) List<String> vesselType,
            @RequestParam(required = false) List<String> flag,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) Boolean confirmed,
            @RequestParam(defaultValue = "false") boolean includeBanned,
            @RequestParam(required = false) Boolean legacy,
            @RequestParam(defaultValue = "false") boolean confirmedOnly,
            @Parameter(description = "Explicit vessel selection; overrides the filter when present")
            @RequestParam(required = false) List<Long> vesselId) {

        VesselFilter filter = new VesselFilter(name, imoNumber, minDwt, maxDwt, minDwcc, maxDwcc,
                minGrain, maxGrain, minBale, maxBale, maxDraft, yearFrom,
                vesselType, flag, companyId, companyName, confirmed, includeBanned, legacy);
        return ResponseEntity.ok(vesselService.ownerEmailContacts(filter, vesselId, confirmedOnly));
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

    @GetMapping("/{id}/links")
    @Operation(summary = "Companies attached to a vessel, with the capacity each acts in")
    public ResponseEntity<List<VesselCompanyLinkResponse>> links(@PathVariable Long id) {
        return ResponseEntity.ok(vesselService.links(id));
    }

    @PutMapping("/{id}/links/{companyId}")
    @Operation(summary = "Attach a company to a vessel as owner, exclusive broker or broker",
            description = "Replaces whatever role that company held on this vessel — a company "
                    + "appears once per vessel. role=owner displaces the previous owner, and "
                    + "role=exclusive_broker demotes the previous exclusive broker to broker. "
                    + "Returns the vessel's full company list.")
    public ResponseEntity<List<VesselCompanyLinkResponse>> setLink(
            @PathVariable Long id,
            @PathVariable Long companyId,
            @RequestParam String role,
            @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(vesselService.setLink(id, companyId, role, notes));
    }

    @DeleteMapping("/{id}/links/{companyId}")
    @Operation(summary = "Detach a company from a vessel, whichever capacity it was in")
    public ResponseEntity<List<VesselCompanyLinkResponse>> removeLink(
            @PathVariable Long id, @PathVariable Long companyId) {
        return ResponseEntity.ok(vesselService.removeLink(id, companyId));
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
