package com.chartering.clean.controller;

import com.chartering.clean.dto.*;
import com.chartering.clean.service.ContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
@Tag(name = "Contacts", description = "Email/phone contacts and their confirmation status")
public class ContactController {

    private final ContactService contactService;

    @GetMapping
    @Operation(summary = "Search contacts by kind/value/company/confirmed")
    public ResponseEntity<PageResponse<ContactResponse>> search(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Boolean confirmed,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(contactService.search(kind, value, companyId, confirmed, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a contact")
    public ResponseEntity<ContactResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(contactService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a contact")
    public ResponseEntity<ContactResponse> create(@Valid @RequestBody ContactRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contactService.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a contact")
    public ResponseEntity<ContactResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody ContactRequest req) {
        return ResponseEntity.ok(contactService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a contact")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        contactService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/confirm")
    @Operation(summary = "Mark a contact reached & confirmed up to date (or clear with ?confirmed=false)")
    public ResponseEntity<ContactResponse> confirm(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean confirmed,
            @RequestBody(required = false) ConfirmRequest req) {
        return ResponseEntity.ok(contactService.setConfirmed(id, confirmed, req));
    }
}
