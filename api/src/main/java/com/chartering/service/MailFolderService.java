package com.chartering.service;

import com.chartering.dto.MailFolderRequest;
import com.chartering.dto.MailFolderResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.MailFolder;
import com.chartering.repository.MailFolderRepository;
import com.chartering.repository.MailMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The app's own mail folders, and the counts the folder rail is built from.
 *
 * <p>The Inbox is included in {@link #listWithCounts()} as a folder with a null id. It has no
 * row of its own — a message in the Inbox is simply a message nothing has filed — but the
 * rail needs it in the same shape as the rest, and giving "unfiled" the same shape as a
 * folder is what keeps it from being a special case in every caller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailFolderService {

    /** What the Inbox is called on screen. It is a label, not a row. */
    public static final String INBOX_LABEL = "Inbox";

    private final MailFolderRepository folders;
    private final MailMessageRepository messages;

    @Transactional(readOnly = true)
    public List<MailFolderResponse> listWithCounts() {
        Map<Long, Long> totals = countsByFolder(messages.countByFolder());
        Map<Long, Long> unread = countsByFolder(messages.countUnreadByFolder());

        List<MailFolderResponse> out = new ArrayList<>();
        // The Inbox leads the rail: it is where everything arrives, and where anything the
        // rules do not claim stays.
        out.add(new MailFolderResponse(null, INBOX_LABEL, null, -1,
                totals.getOrDefault(null, 0L), unread.getOrDefault(null, 0L)));
        for (MailFolder f : folders.findAllByOrderBySortOrderAscNameAsc()) {
            out.add(new MailFolderResponse(f.getId(), f.getName(), f.getNotes(), f.getSortOrder(),
                    totals.getOrDefault(f.getId(), 0L), unread.getOrDefault(f.getId(), 0L)));
        }
        return out;
    }

    @Transactional
    public MailFolderResponse create(MailFolderRequest req) {
        MailFolder folder = new MailFolder();
        apply(folder, req);
        MailFolder saved = folders.save(folder);
        return new MailFolderResponse(saved.getId(), saved.getName(), saved.getNotes(),
                saved.getSortOrder(), 0, 0);
    }

    @Transactional
    public MailFolderResponse update(Long id, MailFolderRequest req) {
        MailFolder folder = folders.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mail folder", id));
        apply(folder, req);
        folders.save(folder);
        return listWithCounts().stream()
                .filter(f -> id.equals(f.id()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Mail folder", id));
    }

    /**
     * Deletes the folder. Its mail returns to the Inbox rather than being deleted with it —
     * the database enforces that (ON DELETE SET NULL), and it is the only defensible reading:
     * deleting a folder is tidying, and tidying should never destroy correspondence.
     *
     * <p>Rules pointing at it go with it, because a rule that files into a folder that no
     * longer exists has nothing left to mean.
     */
    @Transactional
    public void delete(Long id) {
        if (!folders.existsById(id)) {
            throw new ResourceNotFoundException("Mail folder", id);
        }
        folders.deleteById(id);
        log.info("Mail folder {} deleted; its messages are back in the Inbox", id);
    }

    private void apply(MailFolder folder, MailFolderRequest req) {
        String name = req.getName().trim();
        if (INBOX_LABEL.equalsIgnoreCase(name)) {
            // Two things called Inbox in the rail, one of them meaning "unfiled" and one of
            // them not, is a trap worth closing at the point of naming.
            throw new IllegalArgumentException(
                    "\"Inbox\" is where unfiled mail already lives — pick another name.");
        }
        folders.findByNameIgnoringCase(name)
                .filter(other -> !other.getId().equals(folder.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("A folder called \"" + name + "\" already exists.");
                });
        folder.setName(name);
        folder.setNotes(req.getNotes());
        folder.setSortOrder(req.getSortOrder());
    }

    /** {@code [folderId, count]} rows into a map, with the null key kept for the Inbox. */
    private static Map<Long, Long> countsByFolder(List<Object[]> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : rows) {
            out.put((Long) row[0], (Long) row[1]);
        }
        return out;
    }
}
