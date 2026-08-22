package com.chartering.service.mail;

import com.chartering.dto.MailServerFolderResponse;
import com.chartering.model.MailServerFolder;
import com.chartering.model.MailSyncState;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.MailServerFolderRepository;
import com.chartering.repository.MailSyncStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mirror of the mail server's folder tree: what the reader found, and what the rail asks
 * for.
 *
 * <p>Split from {@code ImapMailboxSyncService} for the reason {@code MailIngestService} is:
 * the reader runs on a poller thread with no transaction, and everything here is about the
 * database rather than about IMAP. What arrives is a list of plain {@link Discovered}
 * records — no {@code jakarta.mail} type crosses this line.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailServerFolderService {

    /**
     * Where a folder sits in the rail. The Inbox leads, the owner's own folders follow, and
     * the ones nobody browses for chartering — drafts, sent, junk, deleted — go last.
     *
     * <p>Keyed off SPECIAL-USE rather than off the name, because the names are in whatever
     * language the mailbox was created in: on the mailbox this was built against the four
     * system folders are Черновики, Отправленные, Спам and Корзина, and matching "Trash"
     * would have sorted every one of them as an ordinary folder.
     */
    private static final int ORDER_INBOX = 0;
    private static final int ORDER_OWN = 10;
    private static final int ORDER_WRITING = 20;
    private static final int ORDER_BIN = 30;

    private final MailServerFolderRepository folders;
    private final MailSyncStateRepository syncState;
    private final MailMessageRepository messages;

    /** One folder as the server listed it. The reader's vocabulary, not IMAP's. */
    public record Discovered(
            String fullName,
            String displayName,
            String parentName,
            String separator,
            String specialUse,
            boolean selectable,
            Integer serverTotal,
            Integer serverUnseen) {
    }

    /**
     * Records what the server listed. Folders it no longer lists are marked gone rather than
     * deleted — mail synced out of a folder that has since been emptied and removed is still
     * mail, and it needs the name to show against.
     *
     * @return the folders as stored, in rail order, for the sync loop to walk
     */
    @Transactional
    public List<MailServerFolder> reconcile(List<Discovered> found) {
        Map<String, MailServerFolder> known = new HashMap<>();
        for (MailServerFolder f : folders.findAll()) {
            known.put(f.getFullName(), f);
        }

        Set<String> seen = new HashSet<>();
        List<MailServerFolder> out = new ArrayList<>(found.size());
        for (Discovered d : found) {
            MailServerFolder f = known.get(d.fullName());
            if (f == null) {
                f = new MailServerFolder();
                f.setFullName(d.fullName());
                log.info("New mail folder on the server: {}", d.fullName());
            }
            f.setDisplayName(d.displayName());
            f.setParentName(d.parentName());
            f.setSeparator(d.separator());
            f.setSpecialUse(d.specialUse());
            f.setSelectable(d.selectable());
            // Only overwritten when the server answered. A STATUS that failed on one folder
            // must not blank a count that was right yesterday.
            if (d.serverTotal() != null) f.setServerTotal(d.serverTotal());
            if (d.serverUnseen() != null) f.setServerUnseen(d.serverUnseen());
            f.setSortOrder(orderOf(d.specialUse()));
            f.setLastSeenAt(LocalDateTime.now());
            f.setPresent(true);
            out.add(f);
            seen.add(d.fullName());
        }

        for (MailServerFolder f : known.values()) {
            if (!seen.contains(f.getFullName()) && f.isPresent()) {
                log.info("Mail folder {} is no longer on the server; keeping the row for the "
                        + "mail already synced out of it", f.getFullName());
                f.setPresent(false);
                out.add(f);
            }
        }

        folders.saveAll(out);
        return folders.findByPresentTrueOrderBySortOrderAscFullNameAsc();
    }

    /** The rail: every folder, its two pairs of counts, and how its last sync went. */
    @Transactional(readOnly = true)
    public List<MailServerFolderResponse> listWithCounts() {
        Map<String, Long> totals = byFolder(messages.countByImapFolder());
        Map<String, Long> unread = byFolder(messages.countUnreadByImapFolder());
        Map<String, MailSyncState> states = new HashMap<>();
        for (MailSyncState s : syncState.findAll()) {
            states.put(s.getImapFolder(), s);
        }

        List<MailServerFolderResponse> out = new ArrayList<>();
        for (MailServerFolder f : folders.findAllByOrderBySortOrderAscFullNameAsc()) {
            MailSyncState state = states.get(f.getFullName());
            out.add(new MailServerFolderResponse(
                    f.getFullName(),
                    f.getDisplayName(),
                    f.getParentName(),
                    f.getSpecialUse(),
                    f.isSelectable(),
                    f.isPresent(),
                    f.getSortOrder(),
                    totals.getOrDefault(f.getFullName(), 0L),
                    unread.getOrDefault(f.getFullName(), 0L),
                    f.getServerTotal(),
                    f.getServerUnseen(),
                    state == null ? null : state.getLastSyncAt(),
                    state == null ? null : state.getLastStatus(),
                    state == null ? null : state.getLastError()));
        }
        return out;
    }

    /**
     * The delimiter the server uses, as last listed — what a full name has to be split on to
     * find its children. Falls back to "/" (Zoho's, and the common one) when nothing has
     * been listed yet.
     */
    @Transactional(readOnly = true)
    public String separator() {
        return folders.findAll().stream()
                .map(MailServerFolder::getSeparator)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("/");
    }

    private static int orderOf(String specialUse) {
        if (specialUse == null) return ORDER_OWN;
        return switch (specialUse) {
            case MailServerFolder.INBOX -> ORDER_INBOX;
            case MailServerFolder.DRAFTS, MailServerFolder.SENT -> ORDER_WRITING;
            case MailServerFolder.JUNK, MailServerFolder.TRASH -> ORDER_BIN;
            default -> ORDER_OWN;
        };
    }

    private static Map<String, Long> byFolder(List<Object[]> rows) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                out.put((String) row[0], (Long) row[1]);
            }
        }
        return out;
    }
}
