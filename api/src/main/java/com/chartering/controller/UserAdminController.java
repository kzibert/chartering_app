package com.chartering.controller;

import com.chartering.dto.UserCreateRequest;
import com.chartering.dto.UserPasswordResponse;
import com.chartering.dto.UserResponse;
import com.chartering.dto.UserUpdateRequest;
import com.chartering.security.AuthenticatedUser;
import com.chartering.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Accounts. Reached by desk administrators and platform administrators only (SecurityConfig);
 * which desk each call may touch is {@link UserAdminService}'s decision.
 *
 * <p>No delete: an account is disabled, because its name is on the change log and on every
 * reply it sent, and those must keep meaning somebody.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin: users", description = "Accounts on a desk")
public class UserAdminController {

    private final UserAdminService service;

    @GetMapping
    @Operation(summary = "Accounts on the caller's desk; a platform administrator may name a desk or omit it for all")
    public ResponseEntity<List<UserResponse>> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                                   @RequestParam(required = false) Long tenantId) {
        return ResponseEntity.ok(service.list(caller, tenantId));
    }

    @PostMapping
    @Operation(summary = "Create an account; returns a one-time password when none was typed")
    public ResponseEntity<UserPasswordResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                       @Valid @RequestBody UserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(caller, request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change an account's display name or role")
    public ResponseEntity<UserResponse> update(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @PathVariable Long id,
                                               @Valid @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(service.update(caller, id, request));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable an account; its tokens stop working at once")
    public ResponseEntity<UserResponse> disable(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @PathVariable Long id) {
        return ResponseEntity.ok(service.setEnabled(caller, id, false));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Enable a disabled account")
    public ResponseEntity<UserResponse> enable(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @PathVariable Long id) {
        return ResponseEntity.ok(service.setEnabled(caller, id, true));
    }

    @PostMapping("/{id}/reset-password")
    @Operation(summary = "Give an account a new one-time password, unlock it and revoke its tokens")
    public ResponseEntity<UserPasswordResponse> resetPassword(@AuthenticationPrincipal AuthenticatedUser caller,
                                                              @PathVariable Long id) {
        return ResponseEntity.ok(service.resetPassword(caller, id));
    }
}
