package com.chartering.controller;

import com.chartering.dto.LookupResponse;
import com.chartering.dto.PortLookupResponse;
import com.chartering.dto.TradeAreaResponse;
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
    @Operation(summary = "Ports, each with the trade area it sits on",
            description = "The area is null for the handful of ports nothing has placed yet, "
                    + "which is worth seeing rather than hiding - it is the list somebody "
                    + "should work through.")
    public ResponseEntity<List<PortLookupResponse>> ports() {
        return ResponseEntity.ok(lookupService.ports());
    }

    @GetMapping("/trade-areas")
    @Operation(summary = "The trade-area vocabulary, in dropdown order, with each area's aliases",
            description = "Nested one level: the parent of West Med is the Mediterranean, "
                    + "which is containment and not adjacency. Aliases are the spellings the "
                    + "market writes - \"W.MED\", \"SPAIN MED\", \"W.ITALY\" all name the same "
                    + "water.")
    public ResponseEntity<List<TradeAreaResponse>> tradeAreas() {
        return ResponseEntity.ok(lookupService.tradeAreas());
    }

    @GetMapping("/tonnage-categories")
    @Operation(summary = "Tonnage categories")
    public ResponseEntity<List<LookupResponse>> tonnageCategories() {
        return ResponseEntity.ok(lookupService.tonnageCategories());
    }
}
