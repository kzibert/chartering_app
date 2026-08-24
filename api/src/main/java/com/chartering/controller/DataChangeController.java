package com.chartering.controller;

import com.chartering.dto.DataChangeResponse;
import com.chartering.dto.PageResponse;
import com.chartering.service.DataChangeService;
import com.chartering.service.DataChangeService.ChangeFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/data-changes")
@RequiredArgsConstructor
@Tag(name = "Change log", description = "Who changed what, when, and what it was before")
public class DataChangeController {

    private final DataChangeService service;

    @GetMapping
    @Operation(summary = "Search the change log",
            description = "Newest first by default. An entry with a fieldName is one field of an "
                    + "update, with the before and after values; one without is a whole record "
                    + "created or deleted, and the values are JSON snapshots of it. Pass "
                    + "changeSet to pull back everything one save or one import did.")
    public ResponseEntity<PageResponse<DataChangeResponse>> search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @Parameter(description = "create, update or delete")
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String changedBy,
            @RequestParam(required = false) UUID changeSet,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until,
            @Parameter(description = "Matches the record's label and both values, so a deleted "
                    + "record can be found by something it used to contain")
            @RequestParam(required = false) String text,
            // id descending rather than changedAt: every row of a change set shares one
            // timestamp, so sorting by time alone leaves the fields of a single save in
            // whatever order the database returns them.
            @PageableDefault(size = 25, sort = "id",
                    direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {

        ChangeFilter filter = new ChangeFilter(
                entityType, entityId, operation, field, changedBy, changeSet, from, until, text);
        return ResponseEntity.ok(service.search(filter, pageable));
    }

    @GetMapping("/entity-types")
    @Operation(summary = "The kinds of record the log covers",
            description = "The whitelist in AuditedEntities. Machine-ingested tables (synced "
                    + "mail) and tables that are already history (circulation runs) are not in it.")
    public ResponseEntity<List<String>> entityTypes() {
        return ResponseEntity.ok(service.entityTypes());
    }

    @GetMapping("/users")
    @Operation(summary = "Everyone who has changed anything, for the filter")
    public ResponseEntity<List<String>> users() {
        return ResponseEntity.ok(service.users());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One entry")
    public ResponseEntity<DataChangeResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping("/{id}/revert")
    @Operation(summary = "Put one field back to what this entry says it was",
            description = "Only for an entry that records a single field of an update — see the "
                    + "revertible flag and revertBlockedReason on the entry. Refused when the "
                    + "field has changed again since, so a later edit nobody has looked at is "
                    + "never overwritten; put the newer change back first. The revert is itself "
                    + "an ordinary edit and appears in the log as one.")
    public ResponseEntity<DataChangeResponse> revert(@PathVariable Long id) {
        return ResponseEntity.ok(service.revert(id));
    }
}
