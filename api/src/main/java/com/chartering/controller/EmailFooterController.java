package com.chartering.controller;

import com.chartering.dto.EmailFooterRequest;
import com.chartering.dto.EmailFooterResponse;
import com.chartering.service.EmailFooterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/email-footers")
@RequiredArgsConstructor
@Tag(name = "Email footers", description = "Reusable HTML signature blocks appended to circulars")
public class EmailFooterController {

    private final EmailFooterService service;

    @GetMapping
    @Operation(summary = "List all footers, by name")
    public ResponseEntity<List<EmailFooterResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one footer")
    public ResponseEntity<EmailFooterResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a footer (setting it default demotes the previous one)")
    public ResponseEntity<EmailFooterResponse> create(@Valid @RequestBody EmailFooterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a footer")
    public ResponseEntity<EmailFooterResponse> update(@PathVariable Long id,
                                                      @Valid @RequestBody EmailFooterRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a footer")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
