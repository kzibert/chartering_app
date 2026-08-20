package com.chartering.service.mail;

import com.chartering.config.MailboxProperties;
import com.chartering.exception.MailNotConfiguredException;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only thing in the application that talks to IMAP, and it only ever reads.
 *
 * <p>The mailbox is opened {@link Folder#READ_ONLY}: no flags are set, nothing is moved,
 * nothing is deleted. Folders and rules are the app's own (see {@code MailFolder}), so the
 * worst any mistake in the app can do to the real mailbox is nothing at all. That is a
 * deliberate constraint rather than an unfinished feature — it is what makes it safe to
 * point this at a working mailbox and let rules loose on it.
 *
 * <h2>How much is fetched, and when</h2>
 * <p>The poller wakes on a fixed delay and asks for what has arrived since the last UID it
 * recorded. Two things bound the work:
 * <ul>
 *   <li><b>The first sync</b> has no UID to resume from, so it takes the newest
 *       {@code maxMessagesPerPoll} messages and keeps those within {@code initialSyncDays}.
 *       Pointing the app at a mailbox with fifteen years of mail in it must not mean
 *       downloading fifteen years of mail.</li>
 *   <li><b>Every sync</b> is capped at {@code maxMessagesPerPoll}, oldest first. A backlog
 *       drains over several polls in arrival order, so the stored cursor only ever moves
 *       forward and an interrupted catch-up resumes exactly where it stopped.</li>
 * </ul>
 *
 * <h2>UIDVALIDITY</h2>
 * <p>IMAP UIDs are only meaningful together with the folder's UIDVALIDITY. When a server
 * reissues it — a rebuild, a migration, a restored backup — every UID stored against that
 * folder now points at a different message. That is checked on every connection, and a
 * change means falling back to the date window instead of resuming from a cursor that has
 * quietly started lying. Deduping by Message-ID is what keeps the re-read from producing a
 * second copy of everything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImapMailboxSyncService {

    private final MailboxProperties props;
    private final MailSyncStateRepository syncState;
    private final MailIngestService ingest;
    private final MimeBodyExtractor bodies;

    /**
     * One sync at a time, process-wide. The poller and the tab's Sync button share this:
     * two readers would fetch the same UID range twice, and both would be writing the same
     * cursor at the end of it.
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
            // Not a bad request and not a conflict: the caller asked for something
            // reasonable and the server has not been given what it needs to do it.
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
            // updating for the life of the process, silently.
            log.warn("Mailbox sync failed: {}", e.toString());
            recordFailure(e);
        } finally {
            syncing.set(false);
        }
    }

    private void sync() throws MessagingException {
        MailSyncState state = syncState.findById(props.getFolder()).orElseGet(() -> {
            MailSyncState fresh = new MailSyncState();
            fresh.setImapFolder(props.getFolder());
            return fresh;
        });

        Store store = null;
        Folder folder = null;
        try {
            store = connect();
            folder = store.getFolder(props.getFolder());
            if (!folder.exists()) {
                throw new MessagingException(
                        "No folder called \"" + props.getFolder() + "\" in this mailbox.");
            }
            folder.open(Folder.READ_ONLY);

            long validity = folder instanceof UIDFolder uf ? uf.getUIDValidity() : 0L;
            boolean resumable = state.getLastUid() != null
                    && state.getImapValidity() != null
                    && state.getImapValidity() == validity;
            if (state.getImapValidity() != null && state.getImapValidity() != validity) {
                log.warn("UIDVALIDITY of {} changed ({} -> {}); re-reading the recent window instead "
                                + "of resuming. Message-ID dedupe stops this duplicating anything.",
                        props.getFolder(), state.getImapValidity(), validity);
            }

            Message[] candidates = resumable
                    ? newerThan(folder, state.getLastUid())
                    : recentWindow(folder);

            List<Message> batch = capped(folder, candidates, resumable ? state.getLastUid() : null);
            prefetch(folder, batch);

            List<MailIngestService.Incoming> parsed = new ArrayList<>(batch.size());
            long highestUid = state.getLastUid() == null ? 0L : state.getLastUid();
            for (Message m : batch) {
                Long uid = uidOf(folder, m);
                parsed.add(read(m, uid, validity));
                if (uid != null && uid > highestUid) {
                    highestUid = uid;
                }
            }

            MailIngestService.Result result = ingest.store(parsed);
            recordSuccess(state, validity, highestUid, parsed.size(), result.stored());

        } finally {
            closeQuietly(folder, store);
        }
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
            return recentWindow(folder);
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
    private Message[] recentWindow(Folder folder) throws MessagingException {
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
                props.getFolder(), recent.size(), tail.length, props.getInitialSyncDays());
        return recent.toArray(Message[]::new);
    }

    /**
     * Oldest first, capped. Order matters as much as the cap: the cursor is the highest UID
     * stored, so taking the <em>newest</em> N of a backlog would move it past everything
     * skipped and lose the rest for good.
     */
    private List<Message> capped(Folder folder, Message[] candidates, Long lastUid)
            throws MessagingException {
        List<Message> sorted = new ArrayList<>(Arrays.asList(candidates));
        if (folder instanceof UIDFolder uf) {
            sorted.sort(Comparator.comparingLong(m -> uidQuietly(uf, m)));
        }
        if (sorted.size() > props.getMaxMessagesPerPoll()) {
            log.info("{} messages waiting in {}; taking the oldest {} this pass"
                            + (lastUid == null ? "" : " (resuming above UID " + lastUid + ")"),
                    sorted.size(), props.getFolder(), props.getMaxMessagesPerPoll());
            return sorted.subList(0, props.getMaxMessagesPerPoll());
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

    private MailIngestService.Incoming read(Message m, Long uid, long validity)
            throws MessagingException {
        InternetAddress from = firstAddress(m);
        MimeBodyExtractor.Body body = bodies.extract(m);
        LocalDateTime arrived = arrivalOf(m);

        return new MailIngestService.Incoming(
                messageIdOf(m),
                uid,
                validity,
                props.getFolder(),
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

    /** Same note as {@link #recordSuccess}: the repository save is the transaction. */
    private void recordFailure(Exception e) {
        MailSyncState state = syncState.findById(props.getFolder()).orElseGet(() -> {
            MailSyncState fresh = new MailSyncState();
            fresh.setImapFolder(props.getFolder());
            return fresh;
        });
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
