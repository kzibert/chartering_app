package com.chartering.controller;

import com.chartering.dto.LoginRequest;
import com.chartering.dto.LoginResponse;
import com.chartering.dto.SessionResponse;
import com.chartering.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login, and the one endpoint that answers "is this token still any good".
 *
 * <p>There is no logout endpoint, and there cannot usefully be one: the token is stateless
 * and the server keeps no record of the ones it has issued, so logging out is the browser
 * throwing its copy away. Anything else would need a revocation list — a database table
 * read on every single request — to protect against a case (a token copied off the machine
 * before logging out) that the TTL already closes.
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
    @Operation(summary = "Who the current bearer token belongs to")
    public ResponseEntity<SessionResponse> me(Authentication authentication) {
        return ResponseEntity.ok(new SessionResponse(authentication.getName()));
    }
}
