package com.chartering.service.parser;

import com.chartering.model.MailMessage;
import com.chartering.model.ParseStatus;
import com.chartering.model.ParsedEmail;
import com.chartering.repository.FeedItemRepository;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Add to waiting list" for the cases the integration test does not need a database to show.
 *
 * <p>The per-state rules are covered end to end in {@code WaitingArrivalsIntegrationTest}; these
 * pin the parts that are about the call itself: an id asked for twice is handled once, a row
 * that is already queued is counted without being written again, and each id is told what
 * happened to it, in the order it was asked.
 */
class EmailParseRunnerQueueTest {

    private final ParsedEmailRepository parsedEmails = mock(ParsedEmailRepository.class);
    private final MailMessageRepository messages = mock(MailMessageRepository.class);
    private final FeedItemRepository feedItems = mock(FeedItemRepository.class);
    private final EmailParseRunner runner = new EmailParseRunner(
            parsedEmails, messages, feedItems, null, null, new ObjectMapper(), null, null);

    @Test
    void aDuplicatedIdIsHandledOnce() {
        when(messages.findById(7L)).thenReturn(Optional.of(messageWithBody(7L)));
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.empty());

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(7L, 7L), false);

        assertThat(outcome.queued()).isEqualTo(1);
        assertThat(outcome.skipped()).isZero();
        assertThat(outcome.items()).hasSize(1);
        verify(messages, times(1)).findById(7L);
        verify(parsedEmails, times(1)).save(any());
    }

    @Test
    void aNewRowIsAQueuedRequestWithNoAttempts() {
        when(messages.findById(7L)).thenReturn(Optional.of(messageWithBody(7L)));
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.empty());

        runner.queue(List.of(7L), false);

        ParsedEmail row = savedRow();
        assertThat(row.getStatus()).isEqualTo(ParseStatus.QUEUED);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getError()).isNull();
        assertThat(row.getParsedAt()).isNotNull();
    }

    @Test
    void aRowAlreadyQueuedIsCountedAndNotWrittenAgain() {
        when(messages.findById(7L)).thenReturn(Optional.of(messageWithBody(7L)));
        ParsedEmail queued = new ParsedEmail();
        queued.setStatus(ParseStatus.QUEUED);
        queued.setAttempts(0);
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.of(queued));

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(7L), false);

        assertThat(outcome.queued()).isEqualTo(1);
        assertThat(outcome.skipped()).isZero();
        assertThat(outcome.items()).extracting(EmailParseRunner.QueueOutcome.Item::result)
                .containsExactly(EmailParseRunner.QueueOutcome.Result.ALREADY_QUEUED);
        verify(parsedEmails, never()).save(any());
    }

    @Test
    void aReadRowIsSkippedWithItsStatusAndTimeAndNoWrite() {
        when(messages.findById(7L)).thenReturn(Optional.of(messageWithBody(7L)));
        when(messages.findById(8L)).thenReturn(Optional.of(messageWithBody(8L)));
        OffsetDateTime readAt = OffsetDateTime.parse("2026-10-01T09:30:00+01:00");
        ParsedEmail parsed = new ParsedEmail();
        parsed.setStatus(ParseStatus.PARSED);
        parsed.setParsedAt(readAt);
        ParsedEmail skipped = new ParsedEmail();
        skipped.setStatus(ParseStatus.SKIPPED);
        skipped.setParsedAt(readAt.plusHours(1));
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.of(parsed));
        when(parsedEmails.findByMailMessageId(8L)).thenReturn(Optional.of(skipped));

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(7L, 8L), false);

        assertThat(outcome.queued()).isZero();
        assertThat(outcome.skipped()).isEqualTo(2);
        assertThat(outcome.items()).extracting(
                        EmailParseRunner.QueueOutcome.Item::mailMessageId,
                        EmailParseRunner.QueueOutcome.Item::result,
                        EmailParseRunner.QueueOutcome.Item::parseStatus,
                        EmailParseRunner.QueueOutcome.Item::parsedAt)
                .containsExactly(
                        tuple(7L, EmailParseRunner.QueueOutcome.Result.ALREADY_READ, ParseStatus.PARSED, readAt),
                        tuple(8L, EmailParseRunner.QueueOutcome.Result.ALREADY_READ, ParseStatus.SKIPPED, readAt.plusHours(1)));
        verify(parsedEmails, never()).save(any());
    }

    @Test
    void anIdNotOnThisDeskIsNotFoundAndABlankBodyHasNoText() {
        when(messages.findById(7L)).thenReturn(Optional.empty());
        MailMessage blank = new MailMessage();
        blank.setId(8L);
        blank.setSubject("Blank one");
        blank.setFromAddress("broker@example.test");
        blank.setBodyText("   ");
        when(messages.findById(8L)).thenReturn(Optional.of(blank));

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(7L, 8L), false);

        assertThat(outcome.queued()).isZero();
        assertThat(outcome.skipped()).isEqualTo(2);
        assertThat(outcome.items()).containsExactly(
                new EmailParseRunner.QueueOutcome.Item(7L, EmailParseRunner.QueueOutcome.Result.NOT_FOUND,
                        null, null, null, null),
                new EmailParseRunner.QueueOutcome.Item(8L, EmailParseRunner.QueueOutcome.Result.NO_TEXT,
                        "Blank one", "broker@example.test", null, null));
        verify(parsedEmails, never()).save(any());
    }

    @Test
    void aNullIdIsNotFound() {
        EmailParseRunner.QueueOutcome outcome = runner.queue(Arrays.asList((Long) null), false);

        assertThat(outcome.skipped()).isEqualTo(1);
        assertThat(outcome.items()).containsExactly(
                new EmailParseRunner.QueueOutcome.Item(null, EmailParseRunner.QueueOutcome.Result.NOT_FOUND,
                        null, null, null, null));
    }

    @Test
    void failedAndIgnoredRowsAreRequeuedAndEachOutcomeComesBackInRequestOrder() {
        // One call with all six outcomes, asked in a deliberately unsorted order with a repeat.
        when(messages.findById(1L)).thenReturn(Optional.of(messageWithBody(1L)));
        when(messages.findById(2L)).thenReturn(Optional.of(messageWithBody(2L)));
        when(messages.findById(3L)).thenReturn(Optional.of(messageWithBody(3L)));
        when(messages.findById(4L)).thenReturn(Optional.of(messageWithBody(4L)));
        when(messages.findById(5L)).thenReturn(Optional.of(blankBody(5L)));
        when(messages.findById(6L)).thenReturn(Optional.empty());

        when(parsedEmails.findByMailMessageId(1L)).thenReturn(Optional.empty());
        ParsedEmail queued = new ParsedEmail();
        queued.setStatus(ParseStatus.QUEUED);
        when(parsedEmails.findByMailMessageId(2L)).thenReturn(Optional.of(queued));
        ParsedEmail failed = new ParsedEmail();
        failed.setStatus(ParseStatus.FAILED);
        failed.setAttempts(3);
        failed.setError("Timed out");
        when(parsedEmails.findByMailMessageId(3L)).thenReturn(Optional.of(failed));
        ParsedEmail parsed = new ParsedEmail();
        parsed.setStatus(ParseStatus.PARSED);
        OffsetDateTime readAt = OffsetDateTime.parse("2026-09-15T12:00:00Z");
        parsed.setParsedAt(readAt);
        when(parsedEmails.findByMailMessageId(4L)).thenReturn(Optional.of(parsed));

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(6L, 4L, 1L, 5L, 2L, 3L, 4L), false);

        assertThat(outcome.queued()).isEqualTo(3);
        assertThat(outcome.skipped()).isEqualTo(3);
        assertThat(outcome.items()).extracting(
                        EmailParseRunner.QueueOutcome.Item::mailMessageId,
                        EmailParseRunner.QueueOutcome.Item::result)
                .containsExactly(
                        tuple(6L, EmailParseRunner.QueueOutcome.Result.NOT_FOUND),
                        tuple(4L, EmailParseRunner.QueueOutcome.Result.ALREADY_READ),
                        tuple(1L, EmailParseRunner.QueueOutcome.Result.QUEUED),
                        tuple(5L, EmailParseRunner.QueueOutcome.Result.NO_TEXT),
                        tuple(2L, EmailParseRunner.QueueOutcome.Result.ALREADY_QUEUED),
                        tuple(3L, EmailParseRunner.QueueOutcome.Result.REQUEUED));
        EmailParseRunner.QueueOutcome.Item read = outcome.items().get(1);
        assertThat(read.parseStatus()).isEqualTo(ParseStatus.PARSED);
        assertThat(read.parsedAt()).isEqualTo(readAt);
        assertThat(read.subject()).isEqualTo("Subject 4");
        assertThat(read.fromAddress()).isEqualTo("broker@example.test");
        assertThat(outcome.items().get(0).subject()).isNull();
        assertThat(outcome.items().get(0).fromAddress()).isNull();
        assertThat(outcome.items().get(5).parseStatus()).isNull();
        assertThat(outcome.items().get(5).parsedAt()).isNull();
        assertThat(failed.getStatus()).isEqualTo(ParseStatus.QUEUED);
        assertThat(failed.getAttempts()).isZero();
        assertThat(failed.getError()).isNull();
    }

    @Test
    void aRereadTurnsAReadRowBackToQueuedAsAFirstAttemptAndKeepsItsOldReading() {
        when(messages.findById(7L)).thenReturn(Optional.of(messageWithBody(7L)));
        when(messages.findById(8L)).thenReturn(Optional.of(messageWithBody(8L)));
        OffsetDateTime readAt = OffsetDateTime.parse("2026-10-01T09:30:00+01:00");
        ParsedEmail parsed = new ParsedEmail();
        parsed.setStatus(ParseStatus.PARSED);
        parsed.setAttempts(1);
        parsed.setParsedAt(readAt);
        parsed.setRawJson("{\"old\":true}");
        ParsedEmail skipped = new ParsedEmail();
        skipped.setStatus(ParseStatus.SKIPPED);
        skipped.setAttempts(1);
        skipped.setError("Not a circular");
        skipped.setParsedAt(readAt);
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.of(parsed));
        when(parsedEmails.findByMailMessageId(8L)).thenReturn(Optional.of(skipped));

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(7L, 8L), true);

        assertThat(outcome.queued()).isEqualTo(2);
        assertThat(outcome.skipped()).isZero();
        assertThat(outcome.items()).extracting(
                        EmailParseRunner.QueueOutcome.Item::mailMessageId,
                        EmailParseRunner.QueueOutcome.Item::result,
                        EmailParseRunner.QueueOutcome.Item::parseStatus,
                        EmailParseRunner.QueueOutcome.Item::parsedAt)
                .containsExactly(
                        tuple(7L, EmailParseRunner.QueueOutcome.Result.REREAD, null, null),
                        tuple(8L, EmailParseRunner.QueueOutcome.Result.REREAD, null, null));
        assertThat(parsed.getStatus()).isEqualTo(ParseStatus.QUEUED);
        assertThat(parsed.getAttempts()).isZero();
        assertThat(parsed.getError()).isNull();
        assertThat(parsed.getParsedAt()).isAfter(readAt);
        // The reading fields are not cleared here: the sweep overwrites them when it reads the row.
        assertThat(parsed.getRawJson()).isEqualTo("{\"old\":true}");
        assertThat(skipped.getStatus()).isEqualTo(ParseStatus.QUEUED);
        assertThat(skipped.getAttempts()).isZero();
        assertThat(skipped.getError()).isNull();
        verify(parsedEmails, times(2)).save(any());
    }

    @Test
    void aRereadOfAMessageWithNoTextIsStillSkipped() {
        when(messages.findById(5L)).thenReturn(Optional.of(blankBody(5L)));
        ParsedEmail parsed = new ParsedEmail();
        parsed.setStatus(ParseStatus.PARSED);
        when(parsedEmails.findByMailMessageId(5L)).thenReturn(Optional.of(parsed));

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(5L), true);

        assertThat(outcome.queued()).isZero();
        assertThat(outcome.skipped()).isEqualTo(1);
        assertThat(outcome.items()).containsExactly(
                new EmailParseRunner.QueueOutcome.Item(5L, EmailParseRunner.QueueOutcome.Result.NO_TEXT,
                        "Subject 5", "broker@example.test", null, null));
        assertThat(parsed.getStatus()).isEqualTo(ParseStatus.PARSED);
        verify(parsedEmails, never()).save(any());
    }

    private static MailMessage messageWithBody(long id) {
        MailMessage message = new MailMessage();
        message.setId(id);
        message.setSubject("Subject " + id);
        message.setFromAddress("broker@example.test");
        message.setBodyText("25,000 MT wheat, Chornomorsk to Spain");
        return message;
    }

    private static MailMessage blankBody(long id) {
        MailMessage message = messageWithBody(id);
        message.setBodyText("   ");
        return message;
    }

    private ParsedEmail savedRow() {
        ArgumentCaptor<ParsedEmail> captor = ArgumentCaptor.forClass(ParsedEmail.class);
        verify(parsedEmails).save(captor.capture());
        return captor.getValue();
    }
}
