package com.chartering.service.mail;

import com.chartering.config.MailboxProperties;
import com.chartering.exception.MailNotConfiguredException;
import com.chartering.model.MailServerFolder;
import com.chartering.model.MailSyncState;
import com.chartering.repository.MailSyncStateRepository;
import jakarta.annotation.PreDestroy;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only thing in the application that talks to IMAP, and it only ever reads.
 *
 * <p>The mailbox is opened {@link Folder#READ_ONLY}: no flags are set, nothing is moved,
 * nothing is deleted, and no folder is created, renamed or removed. What the server has is
 * mirrored; what the app decides — read, filed, linked — stays on this side. That is a
 * deliberate constraint rather than an unfinished feature: it is what makes it safe to point
 * this at a working mailbox and let rules loose on it.
 *
 * <h2>Every folder, not just the Inbox</h2>
 * <p>Each poll lists the server's folders, records the tree ({@code mail_server_folders}) and
 * then reads them one after another, each with its own cursor in {@code mail_sync_state}.
 * Reading only the Inbox made everything the mail server had already done invisible: a Zoho
 * filter that drops a report into "DMARC Reports" on arrival means the message is never in
 * the Inbox at all, so the app never saw it. Mirroring the folders is also what shows Zoho's
 * own filing rules — not their text, which IMAP cannot report, but their effect, which is the
 * folder each message ended up in.
 *
 * <p>A folder that fails is recorded as failed and the loop moves on. One folder the server
 * will not open — a permission, a rename mid-sync — must not cost the poll every other folder
 * behind it.
 *
 * <h2>How much is fetched, and when</h2>
 * <p>The poller wakes on a fixed delay and asks each folder for what has arrived above the
 * last UID it recorded. Three things bound the work:
 * <ul>
 *   <li><b>The first sync</b> of a folder has no UID to resume from, so it takes the newest
 *       messages in it and keeps those within {@code initialSyncDays}. Pointing the app at a
 *       mailbox with fifteen years of mail in it must not mean downloading fifteen years of
 *       mail.</li>
 *   <li><b>Every sync</b> is capped at {@code maxMessagesPerPoll} — across all folders
 *       together, not per folder, which is the only reading of it that still bounds a poll
 *       now that a poll visits twelve folders instead of one.</li>
 *   <li><b>The order</b> is the primary folder first, then whichever folder was read longest
 *       ago. A backlog in one folder therefore drains over several polls without starving the
 *       rest, and needs no rotation cursor of its own: the sync times already record whose
 *       turn it is.</li>
 * </ul>
 *
 * <h2>UIDVALIDITY</h2>
 * <p>IMAP UIDs are only meaningful together with the folder's UIDVALIDITY. When a server
 * reissues it — a rebuild, a migration, a restored backup — every UID stored against that
 * folder now points at a different message. That is checked on every connection, and a change
 * means falling back to the date window instead of resuming from a cursor that has quietly
 * started lying. Deduping by Message-ID is what keeps the re-read from producing a second
 * copy of everything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImapMailboxSyncService {

    private final MailboxProperties props;
    private final MailSyncStateRepository syncState;
    private final MailIngestService ingest;
    private final MailServerFolderService serverFolders;
    private final MimeBodyExtractor bodies;

    /**
     * One sync at a time, process-wide. The poller and the tab's Sync button share this: two
     * readers would fetch the same UID range twice, and both would be writing the same cursor
     * at the end of it.
     */
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    /**
     * Manual syncs run here rather than on the request thread. A first sync against a busy
     * mailbox is a couple of hundred bodies over the network — long enough that holding an
     * HTTP request open for it would be its own bug report.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mailbox-sync");
        t.setDaemon(true);
        return t;
    });

    // ---------------------------------------------------------------- entry points

    /**
     * The poller. Skips quietly when the mailbox is not configured — an app that was never
     * given credentials should not spend a login attempt every five minutes proving it.
     */
    @Scheduled(
            fixedDelayString = "${chartering.mailbox.poll-interval-ms:300000}",
            initialDelayString = "${chartering.mailbox.poll-interval-ms:300000}")
    public void poll() {
        if (!isConfigured()) return;
        syncIfIdle();
    }

    /** The Sync button. Returns immediately; the tab watches {@link #isSyncing()}. */
    public void requestSync() {
        if (!isConfigured()) {
            // Not a bad request and not a conflict: the caller asked for something reasonable
            // and the server has not been given what it needs to do it.
            throw new MailNotConfiguredException(
                    "The mailbox is not configured. Missing: " + String.join(", ", missingSettings()));
        }
        worker.submit(this::syncIfIdle);
    }

    public boolean isSyncing() {
        return syncing.get();
    }

    public boolean isConfigured() {
        return props.isEnabled() && missingSettings().isEmpty();
    }

    /** What is missing, named as the environment variable that supplies it. */
    public List<String> missingSettings() {
        List<String> missing = new ArrayList<>();
        if (!props.isEnabled()) missing.add("IMAP_ENABLED=true");
        if (isBlank(props.getHost())) missing.add("IMAP_HOST");
        if (isBlank(props.getUsername())) missing.add("IMAP_USERNAME (or MAIL_USERNAME)");
        if (isBlank(props.getPassword())) missing.add("IMAP_PASSWORD (or MAIL_PASSWORD)");
        return missing;
    }

    // ---------------------------------------------------------------- the sync itself

    private void syncIfIdle() {
        if (!syncing.compareAndSet(false, true)) {
            log.debug("Mailbox sync already running; this one is skipped");
            return;
        }
        try {
            sync();
        } catch (Exception e) {
            // Nothing above this catches: the poller thread dying would stop the mailbox
            // updating for the life of the process, silently. Recorded against the primary
            // folder because a failure here — the login, the folder listing — belongs to the
            // mailbox rather than to any one folder in it.
            log.warn("Mailbox sync failed: {}", e.toString());
            recordFailure(props.getFolder(), e);
        } finally {
            syncing.set(false);
        }
    }

    /**
     * One pass over the whole mailbox: list the folders, then read them until the poll's
     * budget is spent.
     */
    private void sync() throws MessagingException {
        Store store = null;
        try {
            store = connect();
            List<MailServerFolder> tree = serverFolders.reconcile(discover(store));

            int budget = props.getMaxMessagesPerPoll();
            for (MailServerFolder f : inReadingOrder(tree)) {
                if (budget <= 0) {
                    log.info("Poll budget of {} messages is spent; the folders behind it are read "
                            + "on the next poll", props.getMaxMessagesPerPoll());
                    break;
                }
                if (!f.isSelectable()) continue;
                // Empty as of the listing a moment ago. Skipping saves opening it at all,
                // which on the mailbox this was built against is five folders of twelve.
                if (f.getServerTotal() != null && f.getServerTotal() == 0) continue;

                budget -= syncFolder(store, f.getFullName(), budget);
            }
        } finally {
            closeQuietly(null, store);
        }
    }

    /**
     * Reads one folder, and never throws: a folder that cannot be opened is recorded against
     * itself and the caller carries on with the next one.
     *
     * @return how many messages it fetched, which is what it cost the poll's budget
     */
    private int syncFolder(Store store, String name, int budget) {
        MailSyncState state = stateFor(name);
        Folder folder = null;
        try {
            folder = store.getFolder(name);
            if (!folder.exists()) {
                throw new MessagingException("No folder called \"" + name + "\" in this mailbox.");
            }
            folder.open(Folder.READ_ONLY);

            long validity = folder instanceof UIDFolder uf ? uf.getUIDValidity() : 0L;
            boolean resumable = state.getLastUid() != null
                    && state.getImapValidity() != null
                    && state.getImapValidity() == validity;
            if (state.getImapValidity() != null && state.getImapValidity() != validity) {
                log.warn("UIDVALIDITY of {} changed ({} -> {}); re-reading the recent window instead "
                                + "of resuming. Message-ID dedupe stops this duplicating anything.",
                        name, state.getImapValidity(), validity);
            }

            Message[] candidates = resumable
                    ? newerThan(folder, state.getLastUid())
                    : recentWindow(folder, name);

            List<Message> batch = capped(folder, name, candidates, budget,
                    resumable ? state.getLastUid() : null);
            prefetch(folder, batch);

            List<MailIngestService.Incoming> parsed = new ArrayList<>(batch.size());
            long highestUid = state.getLastUid() == null ? 0L : state.getLastUid();
            for (Message m : batch) {
                Long uid = uidOf(folder, m);
                parsed.add(read(m, uid, validity, name));
                if (uid != null && uid > highestUid) {
                    highestUid = uid;
                }
            }

            MailIngestService.Result result = ingest.store(parsed);
            recordSuccess(state, validity, highestUid, parsed.size(), result.stored());
            return parsed.size();

        } catch (Exception e) {
            log.warn("Reading the mail folder {} failed: {}", name, e.toString());
            recordFailure(name, e);
            return 0;
        } finally {
            closeQuietly(folder, null);
        }
    }

    // ---------------------------------------------------------------- the folder tree

    /**
     * Every folder the server lists, with what it says each one holds.
     *
     * <p>{@code list("*")} rather than walking the tree level by level: one command for the
     * whole mailbox, and it does not matter how deep the hierarchy goes. The counts are a
     * STATUS per folder, which is what allows an empty folder to be skipped without opening
     * it, and what lets the tab say "26 on the server, none synced yet" rather than showing
     * something indistinguishable from an empty folder.
     */
    private List<MailServerFolderService.Discovered> discover(Store store) throws MessagingException {
        Folder[] listed = store.getDefaultFolder().list("*");
        List<MailServerFolderService.Discovered> out = new ArrayList<>(listed.length);
        for (Folder f : listed) {
            String fullName = f.getFullName();
            if (isBlank(fullName)) continue;

            boolean holdsMail = (f.getType() & Folder.HOLDS_MESSAGES) != 0;
            out.add(new MailServerFolderService.Discovered(
                    fullName,
                    f.getName(),
                    parentOf(f),
                    String.valueOf(f.getSeparator()),
                    specialUseOf(f, fullName),
                    holdsMail,
                    holdsMail ? countQuietly(f, false) : null,
                    holdsMail ? countQuietly(f, true) : null));
        }
        log.debug("The mailbox lists {} folders", out.size());
        return out;
    }

    private static String parentOf(Folder f) {
        try {
            Folder parent = f.getParent();
            String name = parent == null ? null : parent.getFullName();
            return isBlank(name) ? null : name;
        } catch (MessagingException e) {
            return null;
        }
    }

    /**
     * IMAP SPECIAL-USE, normalised — {@code \Sent}, {@code \Trash} and the rest, which is the
     * only reliable way to tell a system folder from one the owner made. Their names are in
     * whatever language the mailbox was created in: on the mailbox this was built against
     * they are Черновики, Отправленные, Спам and Корзина, and matching on "Trash" would have
     * found none of them.
     *
     * <p>The Inbox is the exception. IMAP has no {@code \Inbox} attribute, because the name
     * INBOX is itself reserved and matched case-insensitively.
     */
    private static String specialUseOf(Folder f, String fullName) {
        if (fullName.equalsIgnoreCase("INBOX")) {
            return MailServerFolder.INBOX;
        }
        if (!(f instanceof IMAPFolder imap)) {
            return null;
        }
        try {
            String[] attributes = imap.getAttributes();
            if (attributes == null) return null;
            for (String attribute : attributes) {
                // Servers write these with a leading backslash — Sent, Trash and the rest are
                // reserved names rather than words — and a few write them without. Stripping
                // whatever leads the word costs nothing and takes both.
                switch (attribute.toLowerCase(Locale.ROOT).replaceFirst("^[^a-z]+", "")) {
                    case "sent" -> {
                        return MailServerFolder.SENT;
                    }
                    case "drafts" -> {
                        return MailServerFolder.DRAFTS;
                    }
                    case "junk", "spam" -> {
                        return MailServerFolder.JUNK;
                    }
                    case "trash" -> {
                        return MailServerFolder.TRASH;
                    }
                    case "archive" -> {
                        return MailServerFolder.ARCHIVE;
                    }
                    default -> {
                        // HasNoChildren, Noinferiors and friends say nothing about use.
                    }
                }
            }
        } catch (MessagingException e) {
            log.debug("No attributes for folder {}: {}", fullName, e.toString());
        }
        return null;
    }

    /** A count from a closed folder, which is a STATUS. Null rather than a guess on failure. */
    private static Integer countQuietly(Folder f, boolean unseenOnly) {
        try {
            return unseenOnly ? f.getUnreadMessageCount() : f.getMessageCount();
        } catch (MessagingException e) {
            log.debug("STATUS of {} failed: {}", f.getFullName(), e.toString());
            return null;
        }
    }

    /**
     * The primary folder first — it is where mail arrives, and the folder the tab opens on —
     * then whichever folder was read longest ago, never-read folders leading.
     *
     * <p>That ordering is the whole of the fairness scheme. With one budget shared by every
     * folder, a fixed order would mean a folder near the end never being reached while a busy
     * folder in front of it keeps spending the budget; ordering by the cursor times the sync
     * already records makes each poll pick up where the last one left off, with no rotation
     * state to keep.
     */
    private List<MailServerFolder> inReadingOrder(List<MailServerFolder> tree) {
        Map<String, LocalDateTime> lastSync = new HashMap<>();
        for (MailSyncState s : syncState.findAll()) {
            if (s.getLastSyncAt() != null) {
                lastSync.put(s.getImapFolder(), s.getLastSyncAt());
            }
        }
        String primary = props.getFolder();
        List<MailServerFolder> queue = new ArrayList<>(tree);
        queue.sort(Comparator
                .comparingInt((MailServerFolder f) -> f.getFullName().equalsIgnoreCase(primary) ? 0 : 1)
                .thenComparing(f -> lastSync.get(f.getFullName()),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(MailServerFolder::getFullName));
        return queue;
    }

    private Store connect() throws MessagingException {
        String protocol = props.isSsl() ? "imaps" : "imap";
        Properties p = new Properties();
        p.put("mail.store.protocol", protocol);
        p.put("mail." + protocol + ".host", props.getHost());
        p.put("mail." + protocol + ".port", String.valueOf(props.getPort()));
        p.put("mail." + protocol + ".ssl.enable", String.valueOf(props.isSsl()));
        p.put("mail." + protocol + ".ssl.trust", props.getHost());
        p.put("mail." + protocol + ".connectiontimeout", String.valueOf(props.getConnectTimeoutMs()));
        p.put("mail." + protocol + ".timeout", String.valueOf(props.getReadTimeoutMs()));
        // Zoho, among others, answers STARTTLS on the plain port; asking for it costs
        // nothing when SSL is already implicit.
        p.put("mail." + protocol + ".starttls.enable", "true");

        Store store = Session.getInstance(p).getStore(protocol);
        store.connect(props.getHost(), props.getPort(), props.getUsername(), props.getPassword());
        return store;
    }

    /** Everything the server has above the cursor. */
    private Message[] newerThan(Folder folder, long lastUid) throws MessagingException {
        if (!(folder instanceof UIDFolder uf)) {
            return recentWindow(folder, folder.getFullName());
        }
        // getMessagesByUID always returns the last message in the folder even when its UID
        // is at or below the start, so the range is filtered afterwards rather than trusted.
        Message[] found = uf.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
        List<Message> above = new ArrayList<>(found.length);
        for (Message m : found) {
            if (m == null) continue;
            long uid = uf.getUID(m);
            if (uid > lastUid) {
                above.add(m);
            }
        }
        return above.toArray(Message[]::new);
    }

    /**
     * The first-sync window: the newest {@code maxMessagesPerPoll} messages in the folder,
     * then only those inside {@code initialSyncDays}.
     *
     * <p>Taken by message number rather than by an IMAP SEARCH on the date. Servers vary in
     * how well they support searching, and a first sync that silently returns nothing
     * because the server disliked the search term is a much worse failure than reading a few
     * message headers and discarding the old ones here.
     */
    private Message[] recentWindow(Folder folder, String name) throws MessagingException {
        int count = folder.getMessageCount();
        if (count <= 0) {
            return new Message[0];
        }
        int from = Math.max(1, count - props.getMaxMessagesPerPoll() + 1);
        Message[] tail = folder.getMessages(from, count);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(props.getInitialSyncDays());
        List<Message> recent = new ArrayList<>(tail.length);
        for (Message m : tail) {
            LocalDateTime arrived = arrivalOf(m);
            if (arrived == null || !arrived.isBefore(cutoff)) {
                recent.add(m);
            }
        }
        log.info("First sync of {}: {} of the newest {} messages are within {} days",
                name, recent.size(), tail.length, props.getInitialSyncDays());
        return recent.toArray(Message[]::new);
    }

    /**
     * Oldest first, capped at what is left of the poll's budget. Order matters as much as
     * the cap: the cursor is the highest UID stored, so taking the <em>newest</em> N of a
     * backlog would move it past everything skipped and lose the rest for good.
     */
    private List<Message> capped(Folder folder, String name, Message[] candidates, int budget,
                                 Long lastUid) throws MessagingException {
        List<Message> sorted = new ArrayList<>(Arrays.asList(candidates));
        if (folder instanceof UIDFolder uf) {
            sorted.sort(Comparator.comparingLong(m -> uidQuietly(uf, m)));
        }
        if (sorted.size() > budget) {
            log.info("{} messages waiting in {}; taking the oldest {} this pass"
                            + (lastUid == null ? "" : " (resuming above UID " + lastUid + ")"),
                    sorted.size(), name, budget);
            return sorted.subList(0, budget);
        }
        return sorted;
    }

    /**
     * Pulls the envelopes, flags and UIDs for the whole batch in one round trip. Without it
     * every header read below is its own request to the server, which is the difference
     * between a sync taking a second and taking a minute.
     */
    private void prefetch(Folder folder, List<Message> batch) throws MessagingException {
        if (batch.isEmpty()) return;
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(UIDFolder.FetchProfileItem.UID);
        folder.fetch(batch.toArray(Message[]::new), fp);
    }

    // ---------------------------------------------------------------- reading one message

    private MailIngestService.Incoming read(Message m, Long uid, long validity, String folderName)
            throws MessagingException {
        InternetAddress from = firstAddress(m);
        MimeBodyExtractor.Body body = bodies.extract(m);
        LocalDateTime arrived = arrivalOf(m);

        return new MailIngestService.Incoming(
                messageIdOf(m),
                uid,
                validity,
                folderName,
                // An address is required by the schema: a message with no parseable sender
                // is still mail that arrived, and hiding it would be worse than labelling it.
                from != null && from.getAddress() != null ? truncate(from.getAddress(), 320) : "unknown",
                from != null ? truncate(from.getPersonal(), 255) : null,
                addresses(m, Message.RecipientType.TO),
                addresses(m, Message.RecipientType.CC),
                m.getSubject(),
                toLocal(sentDateQuietly(m)),
                arrived != null ? arrived : LocalDateTime.now(),
                body.text(),
                body.html(),
                bodies.snippet(body.text()),
                body.hasAttachments(),
                body.attachmentNamesJoined(),
                sizeOf(m),
                m.getFlags().contains(Flags.Flag.SEEN));
    }

    private static String messageIdOf(Message m) {
        try {
            if (m instanceof MimeMessage mime) {
                return truncate(mime.getMessageID(), 998);
            }
        } catch (MessagingException e) {
            log.debug("No readable Message-ID on a message: {}", e.toString());
        }
        return null;
    }

    private static InternetAddress firstAddress(Message m) throws MessagingException {
        var from = m.getFrom();
        if (from == null || from.length == 0) return null;
        return from[0] instanceof InternetAddress ia ? ia : new InternetAddress(from[0].toString());
    }

    private static String addresses(Message m, Message.RecipientType type) {
        try {
            var recipients = m.getRecipients(type);
            if (recipients == null || recipients.length == 0) return null;
            return Arrays.stream(recipients).map(Object::toString)
                    .reduce((a, b) -> a + ", " + b).orElse(null);
        } catch (MessagingException e) {
            // A malformed recipient header is not worth losing the message over.
            return null;
        }
    }

    /**
     * When the server received it, falling back to the Date header. Both can be absent, and
     * the caller substitutes "now" — an inbox row with no date at all sorts nowhere.
     */
    private static LocalDateTime arrivalOf(Message m) {
        try {
            Date received = m.getReceivedDate();
            return toLocal(received != null ? received : m.getSentDate());
        } catch (MessagingException e) {
            return null;
        }
    }

    private static Date sentDateQuietly(Message m) {
        try {
            return m.getSentDate();
        } catch (MessagingException e) {
            return null;
        }
    }

    private static Integer sizeOf(Message m) {
        try {
            int size = m.getSize();
            return size < 0 ? null : size;
        } catch (MessagingException e) {
            return null;
        }
    }

    private static Long uidOf(Folder folder, Message m) {
        return folder instanceof UIDFolder uf ? uidQuietly(uf, m) : null;
    }

    private static long uidQuietly(UIDFolder uf, Message m) {
        try {
            return uf.getUID(m);
        } catch (MessagingException e) {
            return 0L;
        }
    }

    /**
     * The app works in local wall-clock time throughout (see the README on {@code TZ}), so a
     * message read here lands on the same day it would in the mail client beside it.
     */
    private static LocalDateTime toLocal(Date date) {
        return date == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(date.getTime()), ZoneId.systemDefault());
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ---------------------------------------------------------------- state

    /**
     * Not annotated {@code @Transactional}, and deliberately so: this is called from the
     * poller thread on the same bean, where the proxy is not in the path and the annotation
     * would be decoration rather than behaviour. The repository's own save carries the
     * transaction, which is all a single-row upsert needs.
     */
    private void recordSuccess(MailSyncState state, long validity, long highestUid,
                               int fetched, int stored) {
        state.setImapValidity(validity);
        // Only advanced when something was actually read: an empty poll must not move the
        // cursor, and a folder read with no UID support leaves it alone entirely.
        if (highestUid > 0) {
            state.setLastUid(highestUid);
        }
        state.setLastSyncAt(LocalDateTime.now());
        state.setLastStatus(MailSyncState.OK);
        state.setLastError(null);
        state.setLastFetched(fetched);
        state.setLastStored(stored);
        syncState.save(state);
    }

    private MailSyncState stateFor(String folderName) {
        return syncState.findById(folderName).orElseGet(() -> {
            MailSyncState fresh = new MailSyncState();
            fresh.setImapFolder(folderName);
            return fresh;
        });
    }

    /** Same note as {@link #recordSuccess}: the repository save is the transaction. */
    private void recordFailure(String folderName, Exception e) {
        MailSyncState state = stateFor(folderName);
        state.setLastSyncAt(LocalDateTime.now());
        state.setLastStatus(MailSyncState.FAILED);
        // The message, not the stack: this is shown on the tab, and "AuthenticationFailedException:
        // Invalid credentials" is the whole of what the reader needs to act on.
        state.setLastError(e.getMessage() == null ? e.toString() : e.getMessage());
        syncState.save(state);
    }

    private static void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                // expunge=false — the folder was opened read-only and there is nothing of
                // ours to expunge, but saying so explicitly is worth the argument.
                folder.close(false);
            }
        } catch (MessagingException e) {
            log.debug("Closing the mail folder failed: {}", e.toString());
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (MessagingException e) {
            log.debug("Closing the mail store failed: {}", e.toString());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
