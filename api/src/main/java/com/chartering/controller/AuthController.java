package com.chartering.controller;

import com.chartering.dto.ChangePasswordRequest;
import com.chartering.dto.LoginRequest;
import com.chartering.dto.LoginResponse;
import com.chartering.dto.SessionResponse;
import com.chartering.security.AuthenticatedUser;
import com.chartering.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login, the endpoint that answers "is this token still any good", and changing one's own
 * password.
 *
 * <p>There is no logout endpoint: the server keeps no record of the tokens it has issued, so
 * logging out is the browser throwing its copy away. What revokes tokens server-side is the
 * account's {@code token_version} - changing the password here bumps it, which logs that
 * account out everywhere else.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Login and session")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "Exchange username and password for a bearer token")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    @Operation(summary = "Who the current bearer token belongs to, their role and their desk")
    public ResponseEntity<SessionResponse> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(authService.session(user));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Replace the caller's password; returns a fresh token, every other one is revoked")
    public ResponseEntity<LoginResponse> changePassword(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(authService.changePassword(user, request));
    }
}
