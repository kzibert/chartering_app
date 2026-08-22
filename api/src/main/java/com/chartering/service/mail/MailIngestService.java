package com.chartering.service.mail;

import com.chartering.model.Contact;
import com.chartering.model.MailMessage;
import com.chartering.model.MailRule;
import com.chartering.repository.MailMessageRepository;
import com.chartering.service.MailRuleService;
import com.chartering.service.MailboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a batch of messages read off the mail server into rows, once, in one transaction.
 *
 * <p>Split out from {@code ImapMailboxSyncService} for two reasons. The obvious one is that
 * the reader runs on a poller thread with no transaction of its own, and a {@code
 * @Transactional} method called from inside the same bean would not be transactional at all.
 * The better one is that everything here — deduping, linking, filing — is about the database
 * and nothing about IMAP, so it can be tested with a handful of plain records and no mail
 * server anywhere in sight.
 *
 * <h2>What happens to each message on the way in</h2>
 * <ol>
 *   <li><b>Dedupe</b> by Message-ID, falling back to the IMAP UID for the rare message that
 *       has none. A message already stored keeps everything the app owns about it — read,
 *       app folder, links — because that is precisely what a re-fetch would otherwise
 *       overwrite. The one thing taken from the second sighting is <em>where the server has
 *       it now</em>: an IMAP move is a copy into the target folder and a delete from the
 *       source, so a message Zoho refiles turns up again under a new UID, and following it is
 *       what keeps the mirrored folders honest instead of leaving the message showing in a
 *       folder it left last week.</li>
 *   <li><b>Link</b> the sender to a contact, and through it to a company and person.</li>
 *   <li><b>File</b> it by the rules, which may also mark it read.</li>
 * </ol>
 * All three are done for the whole batch with a fixed number of queries: the contacts and
 * the rules are read once, not once per message.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailIngestService {

    private final MailMessageRepository messages;
    private final MailboxService mailbox;
    private final MailRuleService ruleService;

    /**
     * One message as read off the server, before it is anything to this application.
     *
     * <p>A record rather than a half-built entity so that the reader can hand over exactly
     * what the mail carried, and every decision about what that becomes is made here.
     */
    public record Incoming(
            String messageId, Long uid, Long uidValidity, String imapFolder,
            String fromAddress, String fromName, String toAddresses, String ccAddresses,
            String subject, LocalDateTime sentAt, LocalDateTime receivedAt,
            String bodyText, String bodyHtml, String snippet,
            boolean hasAttachments, String attachmentNames, Integer sizeBytes,
            /** The server's \Seen flag, used once — see {@code MailMessage#isRead()}. */
            boolean seen) {
    }

    /** How a batch went, for the sync state and the log line. */
    public record Result(int stored, int skipped, int filed) {
    }

    @Transactional
    public Result store(List<Incoming> batch) {
        if (batch.isEmpty()) {
            return new Result(0, 0, 0);
        }

        Set<String> alreadyStored = existingMessageIds(batch);
        Map<String, Contact> contactsByAddress = mailbox.contactsByAddress(
                batch.stream().map(i -> i.fromAddress().toLowerCase(Locale.ROOT)).distinct().toList());
        List<MailRule> rules = ruleService.enabledRules();

        int stored = 0;
        int skipped = 0;
        int filed = 0;
        int moved = 0;
        for (Incoming in : batch) {
            if (isDuplicate(in, alreadyStored)) {
                skipped++;
                if (followMove(in)) {
                    moved++;
                }
                continue;
            }
            MailMessage m = toEntity(in);
            mailbox.applyAutoLink(m, contactsByAddress.get(in.fromAddress().toLowerCase(Locale.ROOT)));
            if (ruleService.fileByRules(m, rules).moved()) {
                filed++;
            }
            messages.save(m);
            stored++;
            // Guards against a batch that carries the same message twice — a thread the
            // server returned under two UIDs, say. Cheaper than a save-and-catch.
            if (in.messageId() != null) {
                alreadyStored.add(in.messageId());
            }
        }
        if (stored > 0 || skipped > 0) {
            log.info("Mailbox sync: stored {}, skipped {} already known, {} filed by rules, "
                    + "{} followed to another server folder", stored, skipped, filed, moved);
        }
        return new Result(stored, skipped, filed);
    }

    /**
     * The message is already stored — but the server has just handed it over from a different
     * folder, which means it was moved there. Only the server's own identity is updated:
     * where it is, under which UID. Everything the app decided about it stays.
     *
     * @return whether this was in fact a move rather than a re-read of the same place
     */
    private boolean followMove(Incoming in) {
        if (in.messageId() == null || in.messageId().isBlank()) {
            // Identified by folder+UID rather than by Message-ID, so "the same message in a
            // different folder" is not a statement that can be made about it at all.
            return false;
        }
        return messages.findByMessageId(in.messageId())
                .filter(m -> !in.imapFolder().equals(m.getImapFolder()))
                .map(m -> {
                    log.info("Message \"{}\" has moved on the server: {} -> {}",
                            m.getSubject(), m.getImapFolder(), in.imapFolder());
                    m.setImapFolder(in.imapFolder());
                    m.setImapUid(in.uid());
                    m.setImapValidity(in.uidValidity());
                    m.setSyncedAt(LocalDateTime.now());
                    messages.save(m);
                    return true;
                })
                .orElse(false);
    }

    /** One query for the batch's Message-IDs rather than one existence check per message. */
    private Set<String> existingMessageIds(List<Incoming> batch) {
        List<String> ids = batch.stream()
                .map(Incoming::messageId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        return ids.isEmpty() ? new HashSet<>() : new HashSet<>(messages.findExistingMessageIds(ids));
    }

    private boolean isDuplicate(Incoming in, Set<String> alreadyStored) {
        if (in.messageId() != null && !in.messageId().isBlank()) {
            return alreadyStored.contains(in.messageId());
        }
        // No Message-ID: fall back to the server's own pointer. Rare, and the reason the
        // UID triple has a unique index of its own.
        return in.uid() != null && messages.findByImapFolderAndImapValidityAndImapUid(
                in.imapFolder(), in.uidValidity(), in.uid()).isPresent();
    }

    private static MailMessage toEntity(Incoming in) {
        MailMessage m = new MailMessage();
        m.setMessageId(in.messageId());
        m.setImapUid(in.uid());
        m.setImapValidity(in.uidValidity());
        m.setImapFolder(in.imapFolder());
        m.setFromAddress(in.fromAddress());
        m.setFromName(in.fromName());
        m.setToAddresses(in.toAddresses());
        m.setCcAddresses(in.ccAddresses());
        m.setSubject(in.subject());
        m.setSentAt(in.sentAt());
        m.setReceivedAt(in.receivedAt());
        m.setBodyText(in.bodyText());
        m.setBodyHtml(in.bodyHtml());
        m.setSnippet(in.snippet());
        m.setHasAttachments(in.hasAttachments());
        m.setAttachmentNames(in.attachmentNames());
        m.setSizeBytes(in.sizeBytes());
        // The only time the server's flag is consulted. From here on the app owns it,
        // because the app never writes back and so could never keep the two agreeing.
        m.setRead(in.seen());
        m.setSyncedAt(LocalDateTime.now());
        return m;
    }
}
