package com.chartering.controller;

import com.chartering.dto.*;
import com.chartering.service.ContactService;
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
            @RequestParam(required = false) Long personId,
            @RequestParam(required = false) Boolean confirmed,
            @RequestParam(defaultValue = "false") boolean includeBanned,
            @RequestParam(required = false) Boolean legacy,
            // Explicit default sort: without one the row order is physical, so toggling
            // main/confirm on a row makes it jump to the end of the list.
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(contactService.search(
                kind, value, companyId, personId, confirmed, includeBanned, legacy, pageable));
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

    @PatchMapping("/{id}/main")
    @Operation(summary = "Mark a contact as its company's main email/phone (or clear with ?main=false)",
            description = "At most one main email and one main phone per company — promoting a "
                    + "contact demotes the previous holder of that slot. Bulk email-list actions "
                    + "prefer the main email and fall back to the company's first one.")
    public ResponseEntity<ContactResponse> main(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean main) {
        return ResponseEntity.ok(contactService.setMain(id, main));
    }

    @PatchMapping("/{id}/working")
    @Operation(summary = "Flag a contact as not working (or revive it with ?working=true)",
            description = "A non-working email is left out of bulk email-list collection and "
                    + "dropped again when a campaign starts, so a stale email-list entry still "
                    + "cannot be mailed. A company whose every email is flagged not-working is "
                    + "reported with noWorkingEmail=true and can be listed via the company "
                    + "search's noWorkingEmail filter.")
    public ResponseEntity<ContactResponse> working(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean working) {
        return ResponseEntity.ok(contactService.setWorking(id, working));
    }

    @PatchMapping("/{id}/circ")
    @Operation(summary = "Flag an email for use in circulations (or clear with ?circ=false)",
            description = "Any number of addresses per company or person may carry this flag — "
                    + "unlike main, nothing is demoted. Bulk collection then applies: circ "
                    + "addresses if the person has any, else their main address, else all their "
                    + "working addresses. Email contacts only.")
    public ResponseEntity<ContactResponse> circ(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean circ) {
        return ResponseEntity.ok(contactService.setCirc(id, circ));
    }

    @PatchMapping("/{id}/ban")
    @Operation(summary = "Ban a contact as Russian-rooted (or unban with ?banned=false)")
    public ResponseEntity<ContactResponse> ban(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean banned) {
        return ResponseEntity.ok(contactService.setBanned(id, banned));
    }
}
