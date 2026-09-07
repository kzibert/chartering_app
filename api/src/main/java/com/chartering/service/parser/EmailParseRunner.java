package com.chartering.service.parser;

import com.chartering.model.MailMessage;
import com.chartering.model.ParseStatus;
import com.chartering.model.ParsedEmail;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

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
        row.setRawJson(completion.content());

        Extraction extraction;
        try {
            extraction = json.readValue(completion.content(), Extraction.class);
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
