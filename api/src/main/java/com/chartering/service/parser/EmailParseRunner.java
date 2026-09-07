package com.chartering.service.parser;

import com.chartering.model.MailMessage;
import com.chartering.model.ParseStatus;
import com.chartering.model.ParsedEmail;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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
    private final EmailParserClient client;
    private final IntakeService intake;
    private final ObjectMapper json;

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
        row.setAttempts(row.getAttempts() + 1);
        row.setParsedAt(OffsetDateTime.now());

        if (message.getBodyText() == null || message.getBodyText().isBlank()) {
            row.setStatus(ParseStatus.SKIPPED);
            row.setError("The message has no text body — nothing to read.");
            parsedEmails.save(row);
            return null;
        }

        EmailParserClient.Completion completion;
        try {
            completion = client.complete(message.getSubject(), message.getSentAt(),
                    message.getBodyText());
        } catch (EmailParserClient.ParserUnavailableException e) {
            row.setStatus(ParseStatus.FAILED);
            row.setError(e.getMessage());
            // Saved and flushed before the throw, so the failure is on record even though
            // the caller is about to abandon the sweep. Nothing else in this transaction has
            // written, so there is nothing for the rollback to take with it.
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
     * <p>Reversible. {@link #reopen} puts an ignored message back in the queue, because
     * "ignore" is a judgement about an email and judgements are sometimes wrong.
     */
    @Transactional
    public void ignore(Long mailMessageId, String note) {
        MailMessage message = messages.findById(mailMessageId).orElse(null);
        if (message == null) return;

        ParsedEmail row = parsedEmails.findByMailMessageId(mailMessageId)
                .orElseGet(() -> {
                    ParsedEmail fresh = new ParsedEmail();
                    fresh.setMailMessage(message);
                    fresh.setAttempts(0);
                    return fresh;
                });
        row.setStatus(ParseStatus.IGNORED);
        row.setParsedAt(OffsetDateTime.now());
        String reason = storable(note);
        row.setError(reason == null || reason.isBlank()
                ? "Ignored — not to be read." : "Ignored — " + truncate(reason, 3900));
        parsedEmails.save(row);
    }

    /**
     * Put a message back in the queue — the "read it again" on a failed row.
     *
     * <p>Resetting the attempt count is what makes it useful. A message that hit the ceiling
     * while the workstation was switched off is not a message the model cannot read, and
     * there has to be a way to say so that is not editing the database by hand.
     */
    @Transactional
    public void reopen(Long mailMessageId) {
        parsedEmails.findByMailMessageId(mailMessageId).ifPresent(row -> {
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
