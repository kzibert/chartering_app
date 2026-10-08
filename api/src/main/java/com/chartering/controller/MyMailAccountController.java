package com.chartering.controller;

import com.chartering.dto.MailAccountRequest;
import com.chartering.dto.MailAccountResponse;
import com.chartering.service.mail.MyMailAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The caller's own mailbox. "me" because there is no id to name: it is always the caller's. */
@RestController
@RequestMapping("/api/v1/me/mail-account")
@RequiredArgsConstructor
@Tag(name = "My mailbox", description = "The logged-in account's own mailbox")
public class MyMailAccountController {

    private final MyMailAccountService service;

    @GetMapping
    @Operation(summary = "The caller's mailbox: the server's, a saved one, or none. Never the password")
    public ResponseEntity<MailAccountResponse> get() {
        return ResponseEntity.ok(service.get());
    }

    @PutMapping
    @Operation(summary = "Save the caller's mailbox; a blank password keeps the stored one")
    public ResponseEntity<MailAccountResponse> save(@Valid @RequestBody MailAccountRequest request) {
        return ResponseEntity.ok(service.save(request));
    }

    @DeleteMapping
    @Operation(summary = "Remove the caller's saved mailbox; mail already synced stays")
    public ResponseEntity<Void> delete() {
        service.delete();
        return ResponseEntity.noContent().build();
    }
}
