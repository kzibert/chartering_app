package com.chartering.controller;

import com.chartering.dto.BrevoAccountRequest;
import com.chartering.dto.BrevoAccountResponse;
import com.chartering.service.mail.MyBrevoAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The caller's own Brevo key. "me" for the reason the mailbox is: it is always the caller's. */
@RestController
@RequestMapping("/api/v1/me/brevo-account")
@RequiredArgsConstructor
@Tag(name = "My Brevo account", description = "The logged-in account's own Brevo API key")
public class MyBrevoAccountController {

    private final MyBrevoAccountService service;

    @GetMapping
    @Operation(summary = "The caller's Brevo account: a saved one, the server's, or none. Never the key")
    public ResponseEntity<BrevoAccountResponse> get() {
        return ResponseEntity.ok(service.get());
    }

    @PutMapping
    @Operation(summary = "Save the caller's Brevo key and sender; a blank key keeps the stored one")
    public ResponseEntity<BrevoAccountResponse> save(@Valid @RequestBody BrevoAccountRequest request) {
        return ResponseEntity.ok(service.save(request));
    }

    @DeleteMapping
    @Operation(summary = "Remove the caller's saved Brevo key")
    public ResponseEntity<Void> delete() {
        service.delete();
        return ResponseEntity.noContent().build();
    }
}
