package com.chartering.controller;

import com.chartering.dto.TenantCreateRequest;
import com.chartering.dto.TenantCreatedResponse;
import com.chartering.dto.TenantRenameRequest;
import com.chartering.dto.TenantResponse;
import com.chartering.model.TenantStatus;
import com.chartering.security.AuthenticatedUser;
import com.chartering.service.TenantAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Desks. Platform administrators only (SecurityConfig). No delete - see TenantAdminService. */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
@Tag(name = "Admin: tenants", description = "Desks on this installation")
public class TenantAdminController {

    private final TenantAdminService service;

    @GetMapping
    @Operation(summary = "Every desk, with how many accounts it has")
    public ResponseEntity<List<TenantResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    @Operation(summary = "Create a desk and its first administrator")
    public ResponseEntity<TenantCreatedResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                        @Valid @RequestBody TenantCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(caller, request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a desk")
    public ResponseEntity<TenantResponse> rename(@PathVariable Long id,
                                                 @Valid @RequestBody TenantRenameRequest request) {
        return ResponseEntity.ok(service.rename(id, request.getName()));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend a desk: every login and background job for it stops, its data is kept")
    public ResponseEntity<TenantResponse> suspend(@AuthenticationPrincipal AuthenticatedUser caller,
                                                  @PathVariable Long id) {
        return ResponseEntity.ok(service.setStatus(caller, id, TenantStatus.SUSPENDED));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivate a suspended desk")
    public ResponseEntity<TenantResponse> activate(@AuthenticationPrincipal AuthenticatedUser caller,
                                                   @PathVariable Long id) {
        return ResponseEntity.ok(service.setStatus(caller, id, TenantStatus.ACTIVE));
    }
}
