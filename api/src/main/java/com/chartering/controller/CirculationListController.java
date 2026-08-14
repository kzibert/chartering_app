package com.chartering.controller;

import com.chartering.dto.CirculationListEntryRequest;
import com.chartering.dto.CirculationListRequest;
import com.chartering.dto.CirculationListResponse;
import com.chartering.service.CirculationListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Circulation lists: named recipient sets prepared in advance, plus the unnamed draft
 * ("current list") that the Companies/Vessels/People tabs collect into.
 */
@RestController
@RequestMapping("/api/v1/circulation-lists")
@RequiredArgsConstructor
@Validated
@Tag(name = "Circulation lists", description = "Reusable recipient lists for circulars")
public class CirculationListController {

    private final CirculationListService listService;

    @GetMapping
    @Operation(summary = "Saved lists with their entry counts",
            description = "Entries are omitted — fetch a single list to get them. The draft "
                    + "(current) list is not included here; it has its own endpoint.")
    public ResponseEntity<List<CirculationListResponse>> list() {
        return ResponseEntity.ok(listService.list());
    }

    @GetMapping("/current")
    @Operation(summary = "The current list, with its entries",
            description = "The single unnamed draft every tab adds into. Created on first use.")
    public ResponseEntity<CirculationListResponse> current() {
        return ResponseEntity.ok(listService.getDraft());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One list, with its entries")
    public ResponseEntity<CirculationListResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(listService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create an empty named list")
    public ResponseEntity<CirculationListResponse> create(
            @Valid @RequestBody CirculationListRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(listService.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a list or change its notes",
            description = "Entries are untouched. The current list cannot be renamed — "
                    + "save it as a new list instead.")
    public ResponseEntity<CirculationListResponse> update(
            @PathVariable Long id, @Valid @RequestBody CirculationListRequest req) {
        return ResponseEntity.ok(listService.update(id, req));
    }

    @PostMapping("/{id}/copy")
    @Operation(summary = "Copy a list's contents into a new named list",
            description = "\"Save as\" — the source keeps its rows, so collecting into the "
                    + "current list can carry on where it left off.")
    public ResponseEntity<CirculationListResponse> copy(
            @PathVariable Long id, @Valid @RequestBody CirculationListRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(listService.copy(id, req));
    }

    @PostMapping("/{id}/load/{sourceId}")
    @Operation(summary = "Replace a list's contents with another list's",
            description = "Used to load a saved list into the current one before sending.")
    public ResponseEntity<CirculationListResponse> load(
            @PathVariable Long id,
            @Parameter(description = "List to copy the entries from") @PathVariable Long sourceId) {
        return ResponseEntity.ok(listService.replaceEntriesFrom(id, sourceId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a saved list")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        listService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- entries

    @PostMapping("/{id}/entries")
    @Operation(summary = "Add addresses to a list",
            description = "Addresses already on the list are skipped. Returns {added, skipped} "
                    + "so the caller can report \"added 12 (3 already there)\".")
    public ResponseEntity<Map<String, Integer>> addEntries(
            @PathVariable Long id,
            @Valid @RequestBody List<CirculationListEntryRequest> entries) {
        int added = listService.addEntries(id, entries);
        return ResponseEntity.ok(Map.of("added", added, "skipped", entries.size() - added));
    }

    @PostMapping("/{id}/entries/remove")
    @Operation(summary = "Remove addresses from a list, matched by address",
            description = "Used to subtract one list from another — \"take everyone on this "
                    + "saved list off the current one\". Matching on the address rather than on "
                    + "ids means it still works when the same mailbox was collected through two "
                    + "different contacts. Returns {removed, notOnList}. A POST rather than a "
                    + "DELETE because it carries a body, like /copy and /load.")
    public ResponseEntity<Map<String, Integer>> removeEntries(
            @PathVariable Long id, @RequestBody List<String> emails) {
        int removed = listService.removeEntriesByEmail(id, emails);
        // Counted distinctly: asking twice for the same address is one address, and
        // "notOnList" is meant to read as "these weren't there", not as a failure count.
        int requested = (int) emails.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .count();
        return ResponseEntity.ok(Map.of("removed", removed, "notOnList", requested - removed));
    }

    @PutMapping("/{id}/entries/{entryId}")
    @Operation(summary = "Edit one row's address or mail-merge fields",
            description = "Edits the list only — the underlying contact record is not touched.")
    public ResponseEntity<CirculationListResponse> updateEntry(
            @PathVariable Long id, @PathVariable Long entryId,
            @Valid @RequestBody CirculationListEntryRequest req) {
        return ResponseEntity.ok(listService.updateEntry(id, entryId, req));
    }

    @DeleteMapping("/{id}/entries/{entryId}")
    @Operation(summary = "Remove one address from a list")
    public ResponseEntity<Void> removeEntry(@PathVariable Long id, @PathVariable Long entryId) {
        listService.removeEntry(id, entryId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/entries")
    @Operation(summary = "Empty a list without deleting it")
    public ResponseEntity<CirculationListResponse> clear(@PathVariable Long id) {
        return ResponseEntity.ok(listService.clear(id));
    }
}
