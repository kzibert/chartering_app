package com.chartering.controller;

import com.chartering.dto.*;
import com.chartering.service.CompanyService;
import com.chartering.service.CompanyService.CompanyFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/companies")
@RequiredArgsConstructor
@Tag(name = "Companies", description = "Filter companies, view their vessels/contacts, manage confirmation")
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping
    @Operation(summary = "Search companies")
    public ResponseEntity<PageResponse<CompanyResponse>> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Boolean shipowner,
            @RequestParam(required = false) Boolean charterer,
            @RequestParam(required = false) Boolean broker,
            @RequestParam(required = false) Boolean agent,
            @RequestParam(required = false) Boolean confirmed,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long portId,
            @RequestParam(required = false) Long tonnageCategoryId,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {

        CompanyFilter filter = new CompanyFilter(name, city, shipowner, charterer, broker, agent,
                confirmed, regionId, portId, tonnageCategoryId);
        return ResponseEntity.ok(companyService.search(filter, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a company with its contacts and owned vessels")
    public ResponseEntity<CompanyDetailResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(companyService.getDetail(id));
    }

    @GetMapping("/{id}/contacts")
    @Operation(summary = "List a company's contacts")
    public ResponseEntity<List<ContactResponse>> contacts(@PathVariable Long id) {
        return ResponseEntity.ok(companyService.getContacts(id));
    }

    @GetMapping("/{id}/vessels")
    @Operation(summary = "List vessels owned by a company")
    public ResponseEntity<List<VesselResponse>> vessels(@PathVariable Long id) {
        return ResponseEntity.ok(companyService.getVessels(id));
    }

    @PostMapping
    @Operation(summary = "Create a company")
    public ResponseEntity<CompanyResponse> create(@Valid @RequestBody CompanyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyService.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a company")
    public ResponseEntity<CompanyResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody CompanyRequest req) {
        return ResponseEntity.ok(companyService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a company")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        companyService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/confirm")
    @Operation(summary = "Mark a company reached & confirmed up to date (or clear with ?confirmed=false)")
    public ResponseEntity<CompanyResponse> confirm(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean confirmed,
            @RequestBody(required = false) ConfirmRequest req) {
        return ResponseEntity.ok(companyService.setConfirmed(id, confirmed, req));
    }
}
