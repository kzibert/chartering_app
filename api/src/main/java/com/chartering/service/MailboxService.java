package com.chartering.service;

import com.chartering.dto.MailLinkRequest;
import com.chartering.dto.MailMessageDetailResponse;
import com.chartering.dto.MailMessageResponse;
import com.chartering.dto.MailboxSendingResponse;
import com.chartering.dto.PageResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.MailFolder;
import com.chartering.model.MailMessage;
import com.chartering.model.MailServerFolder;
import com.chartering.model.MailSyncState;
import com.chartering.model.Person;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.MailFolderRepository;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.MailReplyRepository;
import com.chartering.repository.MailServerFolderRepository;
import com.chartering.repository.MailSyncStateRepository;
import com.chartering.repository.PersonRepository;
import com.chartering.service.mail.MailServerFolderService;
import com.chartering.specification.MailMessageSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reading the synced mail: searching it, opening it, filing it by hand, and attaching it to
 * a company.
 *
 * <p>Everything here operates on rows in {@code mail_messages}. The mail server is not
 * touched by any of it — see {@code ImapMailboxSyncService}, which is the only thing that
 * talks to IMAP, and which only ever reads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailboxService {

    /** The re-link pass reads mail in pages, for the same reason the rule run does. */
    private static final int RELINK_PAGE_SIZE = 500;

    private final MailMessageRepository messages;
    private final MailFolderRepository folders;
    private final CompanyRepository companies;
    private final PersonRepository people;
    private final ContactRepository contacts;
    private final MailReplyRepository replies;
    private final MailServerFolderRepository serverFolderRows;
    private final MailSyncStateRepository syncState;
    /** Only for the server's folder delimiter, and only when a server folder is filtered on. */
    private final MailServerFolderService serverFolders;
    private final HtmlSanitizer sanitizer;
    private final DtoMapper mapper;

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public PageResponse<MailMessageResponse> search(MailboxFilter f, Pageable pageable) {
        Page<MailMessage> page = messages.findAll(buildSpec(f), pageable);
        return PageResponse.from(page.map(mapper::toMailMessageResponse));
    }

    /**
     * Opens a message.
     *
     * <p>{@code markRead} defaults to on at the controller, because opening a message is what
     * reading it means — every mail client in existence does this, and making it a separate
     * click would mean an inbox whose unread badge never went down on its own.
     */
    @Transactional
    public MailMessageDetailResponse getDetail(Long id, boolean markRead) {
        MailMessage m = messages.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Message", id));
        if (markRead && !m.isRead()) {
            m.setRead(true);
            messages.save(m);
        }
        // Sanitized here rather than at sync time: the stored body stays exactly as it
        // arrived, so a change to what counts as unsafe applies to old mail as well as new,
        // and nothing is lost from the archive by a stripping rule that turns out too broad.
        return mapper.toMailMessageDetail(m, sanitizer.clean(m.getBodyHtml()),
                replies.lastRepliedAt(id));
    }

    /**
     * What this mailbox has sent in a window, whoever sent it.
     *
     * <p>Read out of the Sent folder rather than counted, because most of it was never
     * counted here: a reply written in Outlook, in the webmail or on a phone is invisible to
     * this application until the folder syncs, and then it is simply mail like any other.
     * The folder is found by IMAP SPECIAL-USE — this mailbox calls it "Отправленные", and
     * looking for the word "Sent" would find nothing.
     *
     * <p>The app's own replies are reported beside that figure, not added to it: they are
     * inside it as soon as the folder syncs, and until then they are the only part of the
     * day that is exact.
     */
    @Transactional(readOnly = true)
    public MailboxSendingResponse sendingBetween(LocalDateTime from, LocalDateTime until) {
        int replied = replies.countBySentAtGreaterThanEqualAndSentAtLessThan(from, until);

        Optional<MailServerFolder> sent =
                serverFolderRows.findFirstBySpecialUseAndPresentTrue(MailServerFolder.SENT);
        if (sent.isEmpty()) {
            // No Sent folder the server owns up to. Nothing to report but our own sends —
            // reporting zero would say the mailbox sent nothing, which is a different claim.
            return new MailboxSendingResponse(null, null, null, replied);
        }

        String folder = sent.get().getFullName();
        int inFolder = (int) messages.countByImapFolderAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                folder, from, until);
        LocalDateTime syncedAt = syncState.findById(folder)
                .map(MailSyncState::getLastSyncAt)
                .orElse(null);
        return new MailboxSendingResponse(sent.get().getDisplayName(), inFolder, syncedAt, replied);
    }

    // ---------------------------------------------------------------- read state

    @Transactional
    public MailMessageResponse setRead(Long id, boolean read) {
        MailMessage m = messages.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Message", id));
        m.setRead(read);
        return mapper.toMailMessageResponse(messages.save(m));
    }

    @Transactional
    public int setReadBulk(List<Long> ids, boolean read) {
        List<MailMessage> found = messages.findAllById(ids);
        found.forEach(m -> m.setRead(read));
        messages.saveAll(found);
        return found.size();
    }

    /**
     * Marks read every unread message the filter matches, and returns how many that was.
     *
     * <p>Scoped by the same filter the list is showing rather than sweeping the whole
     * mailbox, because that is the only version of the action anyone can check before
     * clicking it: what it will mark is what is on the screen. "All mail" on the rail with
     * an empty search box is still available, and is then the whole thing.
     *
     * <p>The unread predicate is added here rather than trusted from the caller, so the
     * count returned is messages actually changed and the tab's "Unread only" checkbox
     * cannot turn this into a narrower action than the button says.
     */
    @Transactional
    public int markAllRead(MailboxFilter f) {
        Specification<MailMessage> spec = Specification.allOf(
                buildSpec(f), MailMessageSpecification.readEquals(false));
        List<MailMessage> unread = messages.findAll(spec);
        unread.forEach(m -> m.setRead(true));
        messages.saveAll(unread);
        return unread.size();
    }

    // ---------------------------------------------------------------- filing

    /**
     * Files a message by hand. {@code folderId} null returns it to the Inbox.
     *
     * <p>The move clears the "filed by rule" fingerprint, which is what takes the message out
     * of the rules' reach for good: a hand-filed message is never moved again by a rule (see
     * {@code MailRuleService}). Moving it back to the Inbox by hand puts it back under the
     * rules, which is the only way to undo that and is exactly what the user would expect
     * "put it back" to mean.
     */
    @Transactional
    public MailMessageResponse move(Long id, Long folderId) {
        MailMessage m = messages.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Message", id));
        applyMove(m, resolveFolder(folderId));
        return mapper.toMailMessageResponse(messages.save(m));
    }

    @Transactional
    public int moveBulk(List<Long> ids, Long folderId) {
        MailFolder target = resolveFolder(folderId);
        List<MailMessage> found = messages.findAllById(ids);
        found.forEach(m -> applyMove(m, target));
        messages.saveAll(found);
        return found.size();
    }

    private void applyMove(MailMessage m, MailFolder target) {
        m.setFolder(target);
        m.setFiledByRuleId(null);
        m.setFiledAt(target == null ? null : LocalDateTime.now());
    }

    private MailFolder resolveFolder(Long folderId) {
        if (folderId == null) return null;
        return folders.findById(folderId)
                .orElseThrow(() -> new ResourceNotFoundException("Mail folder", folderId));
    }

    // ---------------------------------------------------------------- the company link

    /**
     * Attaches a message to a company by hand, and optionally records the sender's address
     * as one of that company's contacts.
     *
     * <p>Recording the contact is the half worth doing. Linking the message fixes the one row
     * in front of you; adding the address means every later message from that sender links
     * itself, and that the address is now visible to the rest of the app — the company
     * drawer, the circulation lists, the bulk collect. One is a correction, the other is
     * data entry that happened to start from an email.
     */
    @Transactional
    public MailMessageResponse link(Long id, MailLinkRequest req) {
        MailMessage m = messages.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Message", id));

        if (req.getCompanyId() == null && req.getPersonId() == null) {
            return unlink(m);
        }

        Person person = req.getPersonId() == null ? null : people.findById(req.getPersonId())
                .orElseThrow(() -> new ResourceNotFoundException("Person", req.getPersonId()));
        Company company = req.getCompanyId() != null
                ? companies.findById(req.getCompanyId())
                    .orElseThrow(() -> new ResourceNotFoundException("Company", req.getCompanyId()))
                // A person implies their employer, so picking only the person still files the
                // message under the right company rather than under nothing.
                : person != null ? person.getCompany() : null;

        m.setCompany(company);
        m.setPerson(person);
        m.setLinkManual(true);

        if (req.isCreateContact()) {
            m.setContact(ensureContact(m.getFromAddress(), company, person));
        } else {
            m.setContact(null);
        }
        return mapper.toMailMessageResponse(messages.save(m));
    }

    @Transactional
    public MailMessageResponse unlinkById(Long id) {
        MailMessage m = messages.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Message", id));
        return unlink(m);
    }

    /**
     * Drops the link and hands the message back to the automatic resolver — which is why
     * {@code linkManual} is cleared rather than left set: "this has no company" as a
     * permanent decision would be indistinguishable from "nobody has looked at it yet", and
     * the next re-link pass should be free to try again.
     */
    private MailMessageResponse unlink(MailMessage m) {
        m.setCompany(null);
        m.setPerson(null);
        m.setContact(null);
        m.setLinkManual(false);
        return mapper.toMailMessageResponse(messages.save(m));
    }

    /** The address as a contact of this company, reusing the existing row when there is one. */
    private Contact ensureContact(String address, Company company, Person person) {
        if (company == null && person == null) return null;
        Optional<Contact> existing = contacts.findEmailContactsByAddresses(
                        List.of(address.toLowerCase(Locale.ROOT))).stream()
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        Contact c = new Contact();
        c.setContactKind("email");
        c.setContactValue(address);
        c.setCompany(company != null ? company : person.getCompany());
        c.setPerson(person);
        c.setNotes("Added from the Mailbox tab");
        return contacts.save(c);
    }

    /**
     * Re-resolves every automatic link against the contacts as they are now.
     *
     * <p>Worth running after a batch of contacts is added or a company is merged: the link is
     * resolved once at sync time, so mail that arrived before its sender was known would
     * otherwise stay unattached forever. Links set by hand are left exactly as they are —
     * that is what {@code linkManual} is for.
     *
     * @return how many messages changed
     */
    @Transactional
    public int relinkAll() {
        List<String> senders = messages.findAutoLinkableSenders();
        if (senders.isEmpty()) return 0;

        Map<String, Contact> byAddress = contactsByAddress(senders);

        int changed = 0;
        int page = 0;
        Specification<MailMessage> autoLinked =
                (root, query, cb) -> cb.isFalse(root.get("linkManual"));
        while (true) {
            var slice = messages.findAll(autoLinked,
                    PageRequest.of(page, RELINK_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")));
            if (slice.isEmpty()) break;

            for (MailMessage m : slice) {
                if (applyAutoLink(m, byAddress.get(m.getFromAddress().toLowerCase(Locale.ROOT)))) {
                    changed++;
                }
            }
            messages.saveAll(slice.getContent());
            if (slice.isLast()) break;
            page++;
        }
        log.info("Re-linked mail against contacts: {} of {} distinct senders resolved, {} messages changed",
                byAddress.size(), senders.size(), changed);
        return changed;
    }

    /**
     * The address to contact map used by both the sync and the re-link pass. One query for
     * the whole batch — the alternative, a lookup per message, is the entire cost of a sync.
     * A duplicated address resolves to the lowest contact id, deterministically.
     */
    @Transactional(readOnly = true)
    public Map<String, Contact> contactsByAddress(List<String> lowercaseAddresses) {
        Map<String, Contact> out = new HashMap<>();
        for (Contact c : contacts.findEmailContactsByAddresses(lowercaseAddresses)) {
            out.putIfAbsent(c.getContactValue().toLowerCase(Locale.ROOT), c);
        }
        return out;
    }

    /**
     * Points a message at the contact its sender resolves to, or at nothing.
     *
     * @return true when something actually changed, so bulk callers can report a count
     */
    public boolean applyAutoLink(MailMessage m, Contact match) {
        Long before = m.getCompany() == null ? null : m.getCompany().getId();
        if (match == null) {
            m.setContact(null);
            m.setCompany(null);
            m.setPerson(null);
            return before != null;
        }
        m.setContact(match);
        m.setPerson(match.getPerson());
        // The contact's own company, or the company of the person it belongs to — a contact
        // row may carry either, and mail from a named person should still find the employer.
        Company company = match.getCompany() != null ? match.getCompany()
                : match.getPerson() != null ? match.getPerson().getCompany() : null;
        m.setCompany(company);
        Long after = company == null ? null : company.getId();
        return before == null ? after != null : !before.equals(after);
    }

    // ---------------------------------------------------------------- internals

    private Specification<MailMessage> buildSpec(MailboxFilter f) {
        return Specification.allOf(
                MailMessageSpecification.matches(f.search(), f.includeBody()),
                MailMessageSpecification.inFolder(f.folderId()),
                MailMessageSpecification.unfiled(f.unfiled()),
                MailMessageSpecification.inServerFolder(
                        f.imapFolder(),
                        f.imapFolder() == null ? null : serverFolders.separator()),
                MailMessageSpecification.readEquals(f.read()),
                MailMessageSpecification.companyIdEquals(f.companyId()),
                MailMessageSpecification.hasCompany(f.linked()),
                MailMessageSpecification.receivedFrom(f.receivedFrom()),
                MailMessageSpecification.receivedTo(f.receivedTo()));
    }

    /**
     * What the message list is narrowed by.
     *
     * @param search      the one search box: every term must match somewhere
     * @param includeBody also scan the message text — the expensive half, off by default
     * @param folderId    one app folder
     * @param unfiled     true = the Inbox (nothing has filed it); pair with a null folderId
     * @param imapFolder  one folder on the mail server, and everything nested under it. A
     *                    different axis from the two above and combinable with them: the
     *                    server decides where a message sits, the app's rules decide what is
     *                    filed on top of that
     * @param read        read/unread
     * @param companyId   mail from one company
     * @param linked      true = attached to a company, false = the senders nobody knows yet
     */
    public record MailboxFilter(
            String search, boolean includeBody, Long folderId, Boolean unfiled, String imapFolder,
            Boolean read, Long companyId, Boolean linked,
            LocalDateTime receivedFrom, LocalDateTime receivedTo) {
    }
}
