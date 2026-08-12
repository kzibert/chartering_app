package com.chartering.controller;

import com.chartering.dto.PersonRequest;
import com.chartering.dto.PersonResponse;
import com.chartering.service.PersonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/people")
@RequiredArgsConstructor
@Tag(name = "People", description = "Contact persons attached to companies")
public class PersonController {

    private final PersonService personService;

    @GetMapping
    @Operation(summary = "List people, optionally filtered by company and/or name",
            description = "name is a case-insensitive substring match against the full name "
                    + "or the greeting name. Filters combine (both must match).")
    public ResponseEntity<List<PersonResponse>> list(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String name) {
        return ResponseEntity.ok(personService.list(companyId, name));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a person")
    public ResponseEntity<PersonResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(personService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a person")
    public ResponseEntity<PersonResponse> create(@Valid @RequestBody PersonRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(personService.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a person")
    public ResponseEntity<PersonResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody PersonRequest req) {
        return ResponseEntity.ok(personService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a person")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        personService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
