package com.chartering.controller;

import com.chartering.dto.DeskAliasRequest;
import com.chartering.dto.DeskAliasResponse;
import com.chartering.service.DeskAliasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The desk's own spellings of ports and trade areas. Changing them is for desk administrators (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/vocabulary/aliases")
@RequiredArgsConstructor
@Tag(name = "Desk vocabulary", description = "The desk's own aliases for ports and trade areas")
public class DeskAliasController {

    private final DeskAliasService service;

    @GetMapping
    @Operation(summary = "The aliases this desk added; the market's own are not listed")
    public ResponseEntity<List<DeskAliasResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    @Operation(summary = "Read a spelling as a port or trade area, for this desk only")
    public ResponseEntity<DeskAliasResponse> add(@Valid @RequestBody DeskAliasRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(request));
    }

    @DeleteMapping("/{kind}/{id}")
    @Operation(summary = "Remove one of this desk's aliases")
    public ResponseEntity<Void> delete(@PathVariable String kind, @PathVariable Long id) {
        service.delete(kind, id);
        return ResponseEntity.noContent().build();
    }
}
