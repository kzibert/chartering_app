package com.chartering.controller;

import com.chartering.config.MailboxProperties;
import com.chartering.dto.MailLinkRequest;
import com.chartering.dto.MailMessageDetailResponse;
import com.chartering.dto.MailMessageResponse;
import com.chartering.dto.MailServerFolderResponse;
import com.chartering.dto.MailboxStatusResponse;
import com.chartering.dto.PageResponse;
import com.chartering.model.MailSyncState;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.MailSyncStateRepository;
import com.chartering.service.MailboxService;
import com.chartering.service.MailboxService.MailboxFilter;
import com.chartering.service.mail.ImapMailboxSyncService;
import com.chartering.service.mail.MailServerFolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/mailbox")
@RequiredArgsConstructor
@Tag(name = "Mailbox", description = "Incoming mail synced from IMAP, linked to companies and filed into folders")
public class MailboxController {

    private final MailboxService mailbox;
    private final ImapMailboxSyncService sync;
    private final MailSyncStateRepository syncState;
    private final MailMessageRepository messages;
    private final MailServerFolderService serverFolders;
    private final MailboxProperties props;

    @GetMapping("/messages")
    @Operation(summary = "Search the synced mail",
            description = "One free-text field covers the sender, the subject, the recipients "
                    + "and the linked company or person; every whitespace-separated term must "
                    + "match somewhere, but no term is tied to a particular field. "
                    + "searchBody=true additionally scans the message text — that is an "
                    + "unindexed scan of the largest columns in the table, which is why it is "
                    + "opt-in rather than the default. Scope with imapFolder for a folder on "
                    + "the mail server, with folderId for one of the app's own, or with "
                    + "unfiled=true for the mail no app rule has filed.")
    public ResponseEntity<PageResponse<MailMessageResponse>> search(
            @RequestParam(required = false) String search,
            @Parameter(description = "Also scan the message text. Slower, deliberately opt-in.")
            @RequestParam(defaultValue = "false") boolean searchBody,
            @RequestParam(required = false) Long folderId,
            @Parameter(description = "true = the Inbox: mail no rule and no hand has filed")
            @RequestParam(required = false) Boolean unfiled,
            @Parameter(description = "One folder on the mail server, and everything nested "
                    + "under it — 'INBOX', 'DMARC Reports', 'Brokers/Handy'. A different axis "
                    + "from folderId: the server files the message, the app's rules file on top.")
            @RequestParam(required = false) String imapFolder,
            @RequestParam(required = false) Boolean read,
            @RequestParam(required = false) Long companyId,
            @Parameter(description = "false lists mail from senders that match no contact")
            @RequestParam(required = false) Boolean linked,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime receivedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime receivedTo,
            @PageableDefault(size = 25, sort = "receivedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        MailboxFilter filter = new MailboxFilter(search, searchBody, folderId, unfiled,
                imapFolder, read, companyId, linked, receivedFrom, receivedTo);
        return ResponseEntity.ok(mailbox.search(filter, pageable));
    }

    @GetMapping("/messages/{id}")
    @Operation(summary = "Open a message",
            description = "Returns the full body; the HTML part is sanitized on the way out. "
                    + "Opening marks it read, as every mail client does — pass markRead=false "
                    + "to look without changing anything.")
    public ResponseEntity<MailMessageDetailResponse> get(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean markRead) {
        return ResponseEntity.ok(mailbox.getDetail(id, markRead));
    }

    @PatchMapping("/messages/{id}/read")
    @Operation(summary = "Mark a message read (or unread with ?read=false)")
    public ResponseEntity<MailMessageResponse> setRead(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean read) {
        return ResponseEntity.ok(mailbox.setRead(id, read));
    }

    @PostMapping("/messages/read")
    @Operation(summary = "Mark several messages read (or unread with ?read=false)")
    public ResponseEntity<Integer> setReadBulk(
            @RequestParam(defaultValue = "true") boolean read,
            @RequestBody List<Long> ids) {
        return ResponseEntity.ok(mailbox.setReadBulk(ids, read));
    }

    @PostMapping("/messages/read-all")
    @Operation(summary = "Mark read every unread message in one view of the mail",
            description = "Takes the same scoping parameters as the search, and marks read "
                    + "everything they match — the folder the user is looking at, narrowed by "
                    + "whatever they have typed. Returns how many messages were actually "
                    + "changed, which is unread ones only: mail already read is left alone. "
                    + "With no parameters at all this is the whole mailbox.")
    public ResponseEntity<Integer> markAllRead(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean searchBody,
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) Boolean unfiled,
            @RequestParam(required = false) String imapFolder,
            @RequestParam(required = false) Long companyId) {

        MailboxFilter filter = new MailboxFilter(search, searchBody, folderId, unfiled,
                imapFolder, null, companyId, null, null, null);
        return ResponseEntity.ok(mailbox.markAllRead(filter));
    }

    @PatchMapping("/messages/{id}/folder")
    @Operation(summary = "File a message into a folder",
            description = "Omit folderId to send it back to the Inbox. Filing by hand also "
                    + "takes the message out of the rules' reach: no rule will move it again "
                    + "until it is put back in the Inbox.")
    public ResponseEntity<MailMessageResponse> move(
            @PathVariable Long id,
            @RequestParam(required = false) Long folderId) {
        return ResponseEntity.ok(mailbox.move(id, folderId));
    }

    @PostMapping("/messages/folder")
    @Operation(summary = "File several messages into a folder (omit folderId for the Inbox)")
    public ResponseEntity<Integer> moveBulk(
            @RequestParam(required = false) Long folderId,
            @RequestBody List<Long> ids) {
        return ResponseEntity.ok(mailbox.moveBulk(ids, folderId));
    }

    @PutMapping("/messages/{id}/link")
    @Operation(summary = "Attach a message to a company by hand",
            description = "For mail whose sender is not in the contacts. createContact=true "
                    + "also records the address against the company, so later mail from it "
                    + "links itself and the address becomes visible to the rest of the app.")
    public ResponseEntity<MailMessageResponse> link(
            @PathVariable Long id, @Valid @RequestBody MailLinkRequest req) {
        return ResponseEntity.ok(mailbox.link(id, req));
    }

    @DeleteMapping("/messages/{id}/link")
    @Operation(summary = "Drop a message's company link and hand it back to the auto-matcher")
    public ResponseEntity<MailMessageResponse> unlink(@PathVariable Long id) {
        return ResponseEntity.ok(mailbox.unlinkById(id));
    }

    @PostMapping("/relink")
    @Operation(summary = "Re-resolve every automatic company link against the contacts as they are now",
            description = "Worth running after adding contacts: the link is resolved once at "
                    + "sync time, so mail that arrived before its sender was known stays "
                    + "unattached until this is run. Links set by hand are left alone.")
    public ResponseEntity<Integer> relink() {
        return ResponseEntity.ok(mailbox.relinkAll());
    }

    @GetMapping("/server-folders")
    @Operation(summary = "The mail server's own folder tree, as last listed",
            description = "A read-only mirror of the folders in the mailbox — the app never "
                    + "creates, renames or deletes one. Each carries two pairs of counts: what "
                    + "has been synced into the app, and what the server says the folder holds. "
                    + "A folder the server has stopped listing keeps its row, marked not "
                    + "present, because the mail synced out of it is still here.")
    public ResponseEntity<List<MailServerFolderResponse>> serverFolders() {
        return ResponseEntity.ok(serverFolders.listWithCounts());
    }

    @GetMapping("/status")
    @Operation(summary = "Whether the mailbox is being read, and how the last sync went")
    public ResponseEntity<MailboxStatusResponse> status() {
        return ResponseEntity.ok(buildStatus());
    }

    @PostMapping("/sync")
    @Operation(summary = "Fetch new mail now",
            description = "Returns immediately with 202: the sync runs on its own thread, and "
                    + "the status endpoint reports when it has finished. A sync already in "
                    + "flight is not started twice.")
    public ResponseEntity<MailboxStatusResponse> syncNow() {
        sync.requestSync();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(buildStatus());
    }

    /**
     * The banner's summary of a mailbox that is now read folder by folder.
     *
     * <p>Every folder keeps its own cursor and its own outcome, so the one line at the top of
     * the tab has to say something true about all of them: the newest read of any folder, the
     * totals across the pass, and FAILED the moment any single folder failed — named, because
     * "the sync failed" without saying which folder is not actionable. The per-folder detail
     * is on the rail, beside the folder it belongs to.
     */
    private MailboxStatusResponse buildStatus() {
        List<MailSyncState> states = syncState.findAll();

        LocalDateTime lastSyncAt = states.stream()
                .map(MailSyncState::getLastSyncAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        Optional<MailSyncState> failed = states.stream()
                .filter(s -> MailSyncState.FAILED.equals(s.getLastStatus()))
                .findFirst();
        String status = failed.isPresent() ? MailSyncState.FAILED
                : states.stream().anyMatch(s -> s.getLastStatus() != null) ? MailSyncState.OK
                : null;
        String error = failed
                .map(s -> s.getImapFolder() + ": " + s.getLastError())
                .orElse(null);

        return new MailboxStatusResponse(
                props.isEnabled(),
                sync.isConfigured(),
                sync.missingSettings(),
                props.getHost(),
                props.getFolder(),
                serverFolders.listWithCounts().size(),
                props.getUsername(),
                sync.isSyncing(),
                lastSyncAt,
                status,
                error,
                states.stream().mapToInt(MailSyncState::getLastFetched).sum(),
                states.stream().mapToInt(MailSyncState::getLastStored).sum(),
                props.getPollIntervalMs(),
                messages.count(),
                messages.countByReadFalse());
    }
}
