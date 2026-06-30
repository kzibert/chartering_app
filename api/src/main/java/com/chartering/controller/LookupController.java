package com.chartering.controller;

import com.chartering.dto.LookupResponse;
import com.chartering.service.LookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/lookups")
@RequiredArgsConstructor
@Tag(name = "Lookups", description = "Distinct values / reference data for filter dropdowns")
public class LookupController {

    private final LookupService lookupService;

    @GetMapping("/vessel-types")
    @Operation(summary = "Distinct vessel types")
    public ResponseEntity<List<String>> vesselTypes() {
        return ResponseEntity.ok(lookupService.vesselTypes());
    }

    @GetMapping("/flags")
    @Operation(summary = "Distinct flags")
    public ResponseEntity<List<String>> flags() {
        return ResponseEntity.ok(lookupService.flags());
    }

    @GetMapping("/regions")
    @Operation(summary = "Regions")
    public ResponseEntity<List<LookupResponse>> regions() {
        return ResponseEntity.ok(lookupService.regions());
    }

    @GetMapping("/ports")
    @Operation(summary = "Ports")
    public ResponseEntity<List<LookupResponse>> ports() {
        return ResponseEntity.ok(lookupService.ports());
    }

    @GetMapping("/tonnage-categories")
    @Operation(summary = "Tonnage categories")
    public ResponseEntity<List<LookupResponse>> tonnageCategories() {
        return ResponseEntity.ok(lookupService.tonnageCategories());
    }
}
