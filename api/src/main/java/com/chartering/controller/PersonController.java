package com.chartering.controller;

import com.chartering.dto.PageResponse;
import com.chartering.dto.PersonDetailResponse;
import com.chartering.dto.PersonRequest;
import com.chartering.dto.PersonResponse;
import com.chartering.service.PersonService;
import com.chartering.service.PersonService.PeopleFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

    @GetMapping("/search")
    @Operation(summary = "Paginated people search, each person with their contacts",
            description = "Powers the People page. name matches the full name or greeting name. "
                    + "The contact criteria (contactValue, contactKind, confirmed, legacy) must all "
                    + "be satisfied by the same contact, so kind=email&confirmed=true means "
                    + "\"has a confirmed email\". Banned contacts are ignored, and hidden from the "
                    + "returned lists, unless includeBanned=true. Each match still carries the "
                    + "person's whole contact list, not only the contacts that matched.")
    public ResponseEntity<PageResponse<PersonDetailResponse>> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String contactValue,
            @RequestParam(required = false) String contactKind,
            @RequestParam(required = false) Boolean confirmed,
            @RequestParam(defaultValue = "false") boolean includeBanned,
            @RequestParam(required = false) Boolean legacy,
            @PageableDefault(size = 20, sort = "fullName") Pageable pageable) {

        PeopleFilter filter = new PeopleFilter(
                name, companyId, contactValue, contactKind, confirmed, includeBanned, legacy);
        return ResponseEntity.ok(personService.search(filter, pageable));
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
