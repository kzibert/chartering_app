package com.chartering.service.parser;

import com.chartering.model.FeedItem;
import com.chartering.model.MailMessage;
import com.chartering.model.ParseStatus;
import com.chartering.model.ParsedEmail;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.chartering.service.ParserSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * One message, read and written, in one transaction.
 *
 * <p><b>A bean of its own, and it has to be.</b> {@link ParserSweepService} runs on a worker
 * thread with no transaction, calling this once per message — and a {@code @Transactional}
 * method called from inside the same bean is not transactional at all, because the call never
 * goes through the proxy. {@code MailIngestService} was split out of the IMAP reader for
 * precisely this reason, and the mistake is invisible until something needs to roll back.
 *
 * <p>The better half of the argument is the same one that applies there: everything here is
 * about the database and nothing about HTTP, so it can be reasoned about — and tested —
 * without a model server anywhere in sight.
 *
 * <p><b>Two doors, one method.</b> A board post is a circular somebody pasted onto a public
 * page, which is the same document a broker mails and was read by the same model on the same
 * corpus. So {@link #parsePost} is {@link #parseOne} with a different place to get the subject,
 * the date and the body from — and everything after that, including the row this writes and the
 * questions it raises, is shared. Splitting them further down would have meant two ideas of what
 * "already read" means.
 *
 * <p><b>One transaction per message, not one per sweep.</b> A sweep is forty model calls over
 * several minutes; a transaction around all of it would hold a connection open for the whole
 * run and lose forty good readings to one bad row. Per message, a circular that produced
 * forty positions is committed the moment it is read, and a failure takes only itself down.
 * The model call happens before anything is written, so no transaction is ever open across
 * it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailParseRunner {

    private final ParsedEmailRepository parsedEmails;
    private final MailMessageRepository messages;
    private final com.chartering.repository.FeedItemRepository feedItems;
    private final EmailParserClient client;
    private final IntakeService intake;
    private final ObjectMapper json;
    private final FewShotRetriever shots;
    private final ParserSettings settings;

    /**
     * Read one message.
     *
     * @return what was applied, or null when there was nothing to read or the answer could
     *         not be understood — both of which are recorded on the row rather than thrown,
     *         because neither is a reason to stop the sweep
     * @throws EmailParserClient.ParserUnavailableException when the server did not answer,
     *         which <em>is</em> a reason to stop: the next thirty-nine would fail the same
     *         way and each would cost a timeout
     */
    @Transactional
    public IntakeService.ApplyOutcome parseOne(Long mailMessageId) {
        MailMessage message = messages.findById(mailMessageId).orElse(null);
        if (message == null) return null;

        ParsedEmail row = parsedEmails.findByMailMessageId(mailMessageId)
                .orElseGet(() -> {
                    ParsedEmail fresh = new ParsedEmail();
                    fresh.setMailMessage(message);
                    fresh.setAttempts(0);
                    return fresh;
                });
        return parse(row, message.getSubject(), message.getSentAt(), message.getBodyText(),
                "The message has no text body — nothing to read.");
    }

    /**
     * Read one post off a board — the same reading, through the other door.
     *
     * <p>The subject is the post's title where the page gives one and the source's name where it
     * does not, because the prompt the model was measured under has a Subject line in it and an
     * empty one is a shape the corpus never contained. The date is the board's own date line,
     * which is what makes "SPOT" mean anything: a circular posted on the 14th and read on the
     * 17th announces a laycan relative to the 14th, and handing the model today's date would
     * move every relative date in it by three days.
     */
    @Transactional
    public IntakeService.ApplyOutcome parsePost(Long feedItemId) {
        FeedItem post = feedItems.findById(feedItemId).orElse(null);
        if (post == null) return null;

        ParsedEmail row = parsedEmails.findByFeedItemId(feedItemId)
                .orElseGet(() -> {
                    ParsedEmail fresh = new ParsedEmail();
                    fresh.setFeedItem(post);
                    fresh.setAttempts(0);
                    return fresh;
                });
        // The corpus's own rule for both, not a copy of it: what the model is trained on and
        // what it is asked at inference have to be the same shape, and two implementations of
        // "what stands in for a post's subject" is exactly how that drifts.
        return parse(row,
                com.chartering.service.AnalysisAnnotationTemplates.subjectFor(post),
                com.chartering.service.AnalysisAnnotationTemplates.dateFor(post),
                post.getText(), "The post has no text — nothing to read.");
    }

    /**
     * Everything both doors share: the model call, the row it writes, and what is made of it.
     *
     * <p>Not {@code @Transactional} itself — it is called from the two methods above, which are,
     * and a self-invocation would not go through the proxy anyway. That is the same trap the
     * class exists to avoid at the level above.
     */
    private IntakeService.ApplyOutcome parse(ParsedEmail row, String subject, LocalDateTime when,
                                             String body, String nothingToRead) {
        row.setAttempts(row.getAttempts() + 1);
        row.setParsedAt(OffsetDateTime.now());

        if (body == null || body.isBlank()) {
            row.setStatus(ParseStatus.SKIPPED);
            row.setError(nothingToRead);
            parsedEmails.save(row);
            return null;
        }

        // Retrieved here, beside the model call they feed, because that is the network step this
        // transaction already makes and the one a slow server stalls. Zero-shot when the setting is
        // off, and the retriever answers empty rather than failing when the embedding server is down,
        // so the only way a message fails here is the model itself.
        ParserSettings.FewShot fewShot = settings.fewShot();
        List<FewShotRetriever.Example> examples = fewShot.examples() > 0
                ? shots.examplesFor(subject, when, body, fewShot.examples(), fewShot.maxChars(),
                        List.of(), 0)
                : List.of();
        if (!examples.isEmpty()) {
            log.debug("Parsing with {} few-shot examples: samples {}", examples.size(),
                    examples.stream().map(FewShotRetriever.Example::sampleId).toList());
        }

        EmailParserClient.Completion completion;
        try {
            completion = client.complete(subject, when, body, examples);
        } catch (EmailParserClient.ParserUnavailableException e) {
            row.setStatus(ParseStatus.FAILED);
            row.setError(e.getMessage());
            // Flushed and then rolled back with the rest of the transaction: the exception is
            // a RuntimeException, so the row does not survive and the message stays in the
            // queue with its attempts untouched. That is the right outcome, whatever this
            // once claimed. A server that is down says nothing about the email, and recording
            // it would spend one of the message's attempts per outage until the ceiling took
            // it out of the queue for good. The sweep's report is where an outage is told.
            parsedEmails.saveAndFlush(row);
            throw e;
        }

        row.setModelName(truncate(completion.model(), 160));
        row.setDurationMs(completion.durationMs());
        row.setPromptChars(completion.promptChars());
        // Verbatim but for literal NUL bytes, which the column cannot hold either.
        row.setRawJson(storable(completion.content()));

        Extraction extraction;
        try {
            extraction = json.treeToValue(scrubbed(json.readTree(completion.content())),
                    Extraction.class);
        } catch (Exception e) {
            // The schema makes this close to impossible, and it is still worth handling: the
            // grammar is only applied when the schema resource loaded, and a server running
            // without one answers prose that looks like an answer.
            row.setStatus(ParseStatus.FAILED);
            row.setError("The model's answer was not the expected JSON: " + e.getMessage());
            parsedEmails.save(row);
            return null;
        }

        row.setStatus(ParseStatus.PARSED);
        row.setEmailType(truncate(extraction.type(), 30));
        row.setError(null);
        // Saved first so the extraction has a row to hang review items off. Still one
        // transaction, so a failure below rolls this back with everything else.
        ParsedEmail saved = parsedEmails.save(row);

        IntakeService.ApplyOutcome outcome = intake.apply(saved, extraction);
        saved.setPositionsApplied(outcome.positionsApplied());
        saved.setCargoesApplied(outcome.cargoesApplied());
        saved.setItemsRaised(outcome.itemsRaised());
        return outcome;
    }

    /**
     * Record that reading a message failed, in a transaction of its own.
     *
     * <p><b>Called by the sweep after {@link #parseOne} threw, and it has to be — the row
     * cannot be written by the method that failed.</b> {@code parseOne} is one transaction,
     * so anything thrown out of {@code intake.apply} rolls back the very row that would have
     * recorded the failure; and when the cause is a database error, Postgres has already
     * marked the transaction aborted and refuses every further statement on that connection.
     * Either way the message ends up with no row at all — and the sweep's queue is "synced
     * mail with no row", so it was re-read on every sweep for ever, spending a model call
     * each time, never appearing in the Log's FAILED filter and never reaching the attempt
     * ceiling that exists to stop exactly that.
     *
     * <p>The two failures {@code parseOne} handles itself are unaffected: an unreachable
     * server and an answer that is not JSON are caught before anything else has written, so
     * they can still record themselves. This is for everything after that.
     *
     * <p>The attempt count goes up here as it would have there, so a message that defeats
     * the parser three times stops being offered to it — the whole point of the ceiling.
     */
    @Transactional
    public void recordFailure(Long mailMessageId, String error) {
        MailMessage message = messages.findById(mailMessageId).orElse(null);
        if (message == null) return;

        ParsedEmail row = parsedEmails.findByMailMessageId(mailMessageId)
                .orElseGet(() -> {
                    ParsedEmail fresh = new ParsedEmail();
                    fresh.setMailMessage(message);
                    fresh.setAttempts(0);
                    return fresh;
                });
        markFailed(row, error);
    }

    /** The same, for a post — a board's arrivals need the attempt ceiling exactly as mail does. */
    @Transactional
    public void recordPostFailure(Long feedItemId, String error) {
        FeedItem post = feedItems.findById(feedItemId).orElse(null);
        if (post == null) return;

        ParsedEmail row = parsedEmails.findByFeedItemId(feedItemId)
                .orElseGet(() -> {
                    ParsedEmail fresh = new ParsedEmail();
                    fresh.setFeedItem(post);
                    fresh.setAttempts(0);
                    return fresh;
                });
        markFailed(row, error);
    }

    private void markFailed(ParsedEmail row, String error) {
        row.setAttempts(row.getAttempts() + 1);
        row.setParsedAt(OffsetDateTime.now());
        row.setStatus(ParseStatus.FAILED);
        // Scrubbed, because the commonest cause of landing here is a value Postgres would
        // not take - and a message quoting it would fail to store for the same reason,
        // losing the only description of what went wrong.
        row.setError(truncate(storable(error), 4000));
        parsedEmails.save(row);
    }

    /**
     * Take a message out of the parser's hands for good.
     *
     * <p>The other half of a failure a person has looked at. {@link #reopen} says "try again";
     * this says "never, and stop showing it to me" — for the email that defeats the model
     * every time, the forwarded thread with no position in it, the newsletter. Recorded
     * rather than deleted, for the reason {@code SKIPPED} is: a row is what stops the sweep
     * finding the message again tomorrow and spending another model call on it.
     *
     * <p>Reversible. {@link #reopen} puts an ignored arrival back in the queue, because
     * "ignore" is a judgement about an email and judgements are sometimes wrong.
     *
     * <p>Taken by the id of the parse row rather than of the message, and that is what lets it
     * serve both doors: the Log lists these rows, so whatever is on screen has one, and a
     * message id would have needed a post id beside it and a branch at every call.
     */
    @Transactional
    public void ignore(Long parsedEmailId, String note) {
        ParsedEmail row = parsedEmails.findById(parsedEmailId).orElse(null);
        if (row == null) return;
        row.setStatus(ParseStatus.IGNORED);
        row.setParsedAt(OffsetDateTime.now());
        String reason = storable(note);
        row.setError(reason == null || reason.isBlank()
                ? "Ignored — not to be read." : "Ignored — " + truncate(reason, 3900));
        parsedEmails.save(row);
    }

    /**
     * Add messages to the waiting list: the sweep reads them on its next pass, whatever their age.
     *
     * <p>The same act as "Read into Intake" with the reading left to later. That one starts a run
     * now, and is refused while another run is going; this writes a {@link ParseStatus#QUEUED} row
     * per message and returns, works whether or not a sweep is running, and the timer does the
     * reading on its own terms. The row is what the sweep's queue finds, and
     * it finds queued rows first and outside the lookback, which is the whole point of asking.
     *
     * <p>Per id, in the order given and each once:
     * <ul>
     *   <li>not on this desk, or no text body → skipped. A body is what the model reads, and a
     *       queued row for a message with none would sit outside the sweep's queue for ever.</li>
     *   <li>already {@code PARSED} or {@code SKIPPED} → skipped; it has been read.</li>
     *   <li>already {@code QUEUED} → queued, and left as it is.</li>
     *   <li>{@code FAILED} or {@code IGNORED} → turned back to {@code QUEUED} with the attempt count
     *       and error cleared, so the sweep reads it as a first attempt. A person asking again is a
     *       new request, not another failure of the old one.</li>
     *   <li>no row → a {@code QUEUED} row is created.</li>
     * </ul>
     *
     * <p>Each message is loaded with {@code findById}, not referenced, for the reason {@link #leaveOut}
     * gives: an id from another desk must come back empty rather than be written against.
     *
     * <p>Each id gets one {@link QueueOutcome.Item} saying which of these happened to it, in the
     * order the ids were given, each once. The subject and sender are the message's own, so a
     * person reading the answer can tell which email an id was.
     *
     * @return how many were queued, counting those already queued, how many were skipped, and what
     *         became of each id
     */
    @Transactional
    public QueueOutcome queue(List<Long> mailMessageIds) {
        OffsetDateTime now = OffsetDateTime.now();
        List<QueueOutcome.Item> items = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(mailMessageIds)) {
            if (id == null) {
                items.add(new QueueOutcome.Item(null, QueueOutcome.Result.NOT_FOUND, null, null, null, null));
                continue;
            }
            MailMessage message = messages.findById(id).orElse(null);
            if (message == null) {
                items.add(new QueueOutcome.Item(id, QueueOutcome.Result.NOT_FOUND, null, null, null, null));
                continue;
            }
            String subject = message.getSubject();
            String fromAddress = message.getFromAddress();
            if (message.getBodyText() == null || message.getBodyText().isBlank()) {
                items.add(new QueueOutcome.Item(id, QueueOutcome.Result.NO_TEXT, subject, fromAddress, null, null));
                continue;
            }
            Optional<ParsedEmail> existing = parsedEmails.findByMailMessageId(id);
            if (existing.isEmpty()) {
                ParsedEmail row = new ParsedEmail();
                row.setMailMessage(message);
                row.setAttempts(0);
                row.setStatus(ParseStatus.QUEUED);
                row.setParsedAt(now);
                parsedEmails.save(row);
                items.add(new QueueOutcome.Item(id, QueueOutcome.Result.QUEUED, subject, fromAddress, null, null));
                continue;
            }
            ParsedEmail row = existing.get();
            switch (row.getStatus()) {
                case QUEUED -> items.add(new QueueOutcome.Item(
                        id, QueueOutcome.Result.ALREADY_QUEUED, subject, fromAddress, null, null));
                case FAILED, IGNORED -> {
                    row.setStatus(ParseStatus.QUEUED);
                    row.setAttempts(0);
                    row.setError(null);
                    row.setParsedAt(now);
                    parsedEmails.save(row);
                    items.add(new QueueOutcome.Item(id, QueueOutcome.Result.REQUEUED, subject, fromAddress, null, null));
                }
                case PARSED, SKIPPED -> items.add(new QueueOutcome.Item(
                        id, QueueOutcome.Result.ALREADY_READ, subject, fromAddress,
                        row.getStatus(), row.getParsedAt()));
            }
        }
        int queued = (int) items.stream().filter(i -> i.result() == QueueOutcome.Result.QUEUED
                || i.result() == QueueOutcome.Result.REQUEUED
                || i.result() == QueueOutcome.Result.ALREADY_QUEUED).count();
        return new QueueOutcome(queued, items.size() - queued, items);
    }

    /**
     * What {@link #queue} did: requests recorded, ids passed over, and what happened to each id.
     *
     * <p>Queued counts requests a row now stands for, including ones that were already queued, so
     * pressing the button twice reports the same figure both times. Skipped is everything else.
     *
     * <p>{@code items} has one entry per distinct id asked for, in the order asked. A null id has
     * no message to name, so it comes back {@link Result#NOT_FOUND} with a null id.
     */
    public record QueueOutcome(int queued, int skipped, List<Item> items) {

        /** What became of one requested id. */
        public enum Result {
            /** No row existed; a QUEUED row was created. */
            QUEUED,
            /** The row was FAILED or IGNORED and is QUEUED again as a first attempt. */
            REQUEUED,
            /** The row was already QUEUED and was left as it is. */
            ALREADY_QUEUED,
            /** The row is PARSED or SKIPPED; nothing was written. */
            ALREADY_READ,
            /** The message is on this desk but has no body text to read. */
            NO_TEXT,
            /** Null, or not a message on this desk. */
            NOT_FOUND
        }

        /**
         * One id's outcome. {@code subject} and {@code fromAddress} come from the message and are
         * null when it was not found. {@code parseStatus} and {@code parsedAt} are the existing row's,
         * and are set only for {@link Result#ALREADY_READ}.
         */
        public record Item(Long mailMessageId, Result result, String subject, String fromAddress,
                           ParseStatus parseStatus, OffsetDateTime parsedAt) {
        }
    }

    /**
     * Take arrivals off the waiting list before the sweep reads them.
     *
     * <p>The same judgement {@link #ignore} makes, but made before the model has been asked
     * rather than after it failed: a circular a person can see is a newsletter, a forwarded
     * thread, a delivery notice, goes out of the queue now instead of costing a model call to
     * learn it. It is written as an {@code IGNORED} row for the reason {@code ignore} is: the
     * row is what stops the sweep finding the arrival again, and the Log's existing Reopen puts
     * it back. No new status and no deletion, so there is no new state to learn; a deleted row
     * would be found and read again by tomorrow's sweep.
     *
     * <p>An arrival that already has a row is left alone and not counted, with one exception: a row
     * {@link ParseStatus#QUEUED} is still waiting, so it is turned into this verdict and counted —
     * the person who queued it has changed their mind. Any other status means it is no longer
     * waiting, and overwriting its verdict with "left out" would be rewriting what the model or a
     * person already decided. An id not on this desk is skipped the same way, so the count is of
     * arrivals taken off the list and says nothing about ids that were not this desk's.
     *
     * <p>Each id is loaded, not referenced, although the row only needs to point at it: a
     * reference to an id a caller sent skips the load that would have found it is another desk's,
     * and the foreign key does not look at desks ({@code TenantScopeTest} holds that line). It
     * costs the bodies of the messages picked, which is a person's selection on one button press
     * and not a timer re-reading a table - the case the transfer budget is about.
     *
     * @return how many arrivals came off the waiting list: a new row, or a queued row turned to IGNORED
     */
    @Transactional
    public int leaveOut(List<Long> mailMessageIds, List<Long> feedItemIds, String note) {
        String reason = storable(note);
        String error = reason == null || reason.isBlank()
                ? "Left out of the queue — not to be read."
                : "Left out of the queue — " + truncate(reason, 3900);

        int created = 0;
        if (mailMessageIds != null) {
            for (Long id : mailMessageIds) {
                if (id == null) continue;
                Optional<ParsedEmail> existing = parsedEmails.findByMailMessageId(id);
                if (existing.isPresent()) {
                    if (takeOffQueue(existing.get(), error)) created++;
                    continue;
                }
                MailMessage message = messages.findById(id).orElse(null);
                if (message == null) continue;
                ParsedEmail row = leftOutRow(error);
                row.setMailMessage(message);
                parsedEmails.save(row);
                created++;
            }
        }
        if (feedItemIds != null) {
            for (Long id : feedItemIds) {
                if (id == null) continue;
                Optional<ParsedEmail> existing = parsedEmails.findByFeedItemId(id);
                if (existing.isPresent()) {
                    if (takeOffQueue(existing.get(), error)) created++;
                    continue;
                }
                FeedItem post = feedItems.findById(id).orElse(null);
                if (post == null) continue;
                ParsedEmail row = leftOutRow(error);
                row.setFeedItem(post);
                parsedEmails.save(row);
                created++;
            }
        }
        return created;
    }

    /**
     * Turn a queued row into the left-out verdict. Any other row is not touched.
     *
     * <p>Attempts are left alone: a queued row has none, and the verdict should not claim one.
     *
     * @return whether the row changed
     */
    private boolean takeOffQueue(ParsedEmail row, String error) {
        if (row.getStatus() != ParseStatus.QUEUED) return false;
        row.setStatus(ParseStatus.IGNORED);
        row.setError(error);
        row.setParsedAt(OffsetDateTime.now());
        parsedEmails.save(row);
        return true;
    }

    /**
     * The row shared by both doors. Only the parse state is set here: the counters default to
     * zero, the tenant and timestamps are stamped by Hibernate, and exactly one of the message
     * and the post is set by the caller, which is what the table's check constraint requires.
     *
     * <p>{@code attempts} is zero rather than the entity's default of one: nothing was attempted,
     * and a row that claimed an attempt would be one the Log counts against the retry ceiling.
     */
    private static ParsedEmail leftOutRow(String error) {
        ParsedEmail row = new ParsedEmail();
        row.setStatus(ParseStatus.IGNORED);
        row.setAttempts(0);
        row.setParsedAt(OffsetDateTime.now());
        row.setError(error);
        return row;
    }

    /**
     * Put a message back in the queue — the "read it again" on a failed row.
     *
     * <p>Resetting the attempt count is what makes it useful. A message that hit the ceiling
     * while the workstation was switched off is not a message the model cannot read, and
     * there has to be a way to say so that is not editing the database by hand.
     */
    @Transactional
    public void reopen(Long parsedEmailId) {
        parsedEmails.findById(parsedEmailId).ifPresent(row -> {
            // Already waiting for the sweep. Turning it into a failure would give it a retry state
            // it has not earned, and the Log, where Reopen is pressed, never lists a queued row.
            if (row.getStatus() == ParseStatus.QUEUED) return;
            row.setAttempts(0);
            row.setStatus(ParseStatus.FAILED);
            row.setError("Queued to be read again.");
        });
    }

    /**
     * The model's answer with anything the database cannot hold taken out of it.
     *
     * <p><b>A NUL is the one character Postgres refuses outright</b> — not escaped, not
     * replaced: {@code invalid byte sequence for encoding "UTF8": 0x00}, and the statement
     * fails. A model under a JSON grammar can emit a u0000 escape in a string, and Jackson
     * decodes that escape into a real NUL like any other. It then travelled as an ordinary
     * field value into the first query that touched it, which failed the whole parse of an
     * email the model had in fact read correctly.
     *
     * <p>Scrubbed here, once, on the decoded tree rather than on each field downstream: the
     * extraction is a record tree dozens of strings deep and every one of them ends up in a
     * query or a column. {@code rawJson} keeps the answer as it arrived, minus only literal
     * NUL bytes it could not be stored with — the u0000 escape is six ordinary
     * characters and survives, so what the model said is still legible in the drawer.
     *
     * <p>Package-private so the scrub can be tested on its own, the way the scraper's parse
     * is: it is the half of this that has to be right for every shape the model can produce,
     * and a test that needed a model server would exercise none of it.
     */
    JsonNode scrubbed(JsonNode node) {
        if (node.isTextual()) {
            String raw = node.textValue();
            String clean = storable(raw);
            return clean != null && clean.equals(raw) ? node : json.getNodeFactory().textNode(clean);
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            // Names collected first: replacing a value while iterating the live field names
            // happens to be safe today and is not worth depending on.
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode cleaned = scrubbed(obj.get(name));
                if (cleaned != obj.get(name)) obj.set(name, cleaned);
            }
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode cleaned = scrubbed(arr.get(i));
                if (cleaned != arr.get(i)) arr.set(i, cleaned);
            }
            return arr;
        }
        return node;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * A string with the one character Postgres will not take removed from it.
     *
     * <p>Only the NUL, and deliberately only the NUL. Every other control character stores
     * and reads back fine, and a scrubber that tidied whitespace here would be quietly
     * editing what the model said — which is the one thing the raw answer exists to preserve.
     */
    static String storable(String s) {
        if (s == null || s.indexOf(0) < 0) return s;
        return s.replace(String.valueOf((char) 0), "");
    }
}
