package com.chartering.controller;

import com.chartering.dto.EmailTemplateRequest;
import com.chartering.dto.EmailTemplateResponse;
import com.chartering.service.EmailTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/email-templates")
@RequiredArgsConstructor
@Tag(name = "Email templates", description = "Saved circular subject + HTML body, reusable across campaigns")
public class EmailTemplateController {

    private final EmailTemplateService service;

    @GetMapping
    @Operation(summary = "List all saved templates, by name")
    public ResponseEntity<List<EmailTemplateResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one template")
    public ResponseEntity<EmailTemplateResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping
    @Operation(summary = "Save a new template")
    public ResponseEntity<EmailTemplateResponse> create(@Valid @RequestBody EmailTemplateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a template")
    public ResponseEntity<EmailTemplateResponse> update(@PathVariable Long id,
                                                        @Valid @RequestBody EmailTemplateRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a template")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
