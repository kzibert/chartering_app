package com.chartering.controller;

import com.chartering.dto.MailFolderRequest;
import com.chartering.dto.MailFolderResponse;
import com.chartering.service.MailFolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/mailbox/folders")
@RequiredArgsConstructor
@Tag(name = "Mailbox folders", description = "The app's own mail folders — never IMAP folders")
public class MailFolderController {

    private final MailFolderService folders;

    @GetMapping
    @Operation(summary = "The folder rail, with message and unread counts",
            description = "The Inbox comes first, with a null id: it is not a stored folder "
                    + "but the absence of one, i.e. mail nothing has filed yet.")
    public ResponseEntity<List<MailFolderResponse>> list() {
        return ResponseEntity.ok(folders.listWithCounts());
    }

    @PostMapping
    @Operation(summary = "Create a folder")
    public ResponseEntity<MailFolderResponse> create(@Valid @RequestBody MailFolderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(folders.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename or reorder a folder")
    public ResponseEntity<MailFolderResponse> update(
            @PathVariable Long id, @Valid @RequestBody MailFolderRequest req) {
        return ResponseEntity.ok(folders.update(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a folder",
            description = "Its mail returns to the Inbox rather than being deleted with it. "
                    + "Rules that filed into it go too — a rule with no target means nothing.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        folders.delete(id);
        return ResponseEntity.noContent().build();
    }
}
