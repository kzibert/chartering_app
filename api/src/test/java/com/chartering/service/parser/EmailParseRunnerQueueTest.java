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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
 * pin the parts that are about the call itself: an id asked for twice is handled once, and a row
 * that is already queued is counted without being written again.
 */
class EmailParseRunnerQueueTest {

    private final ParsedEmailRepository parsedEmails = mock(ParsedEmailRepository.class);
    private final MailMessageRepository messages = mock(MailMessageRepository.class);
    private final FeedItemRepository feedItems = mock(FeedItemRepository.class);
    private final EmailParseRunner runner = new EmailParseRunner(
            parsedEmails, messages, feedItems, null, null, new ObjectMapper(), null, null);

    @Test
    void aDuplicatedIdIsHandledOnce() {
        when(messages.findById(7L)).thenReturn(Optional.of(messageWithBody()));
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.empty());

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(7L, 7L));

        assertThat(outcome).isEqualTo(new EmailParseRunner.QueueOutcome(1, 0));
        verify(messages, times(1)).findById(7L);
        verify(parsedEmails, times(1)).save(any());
    }

    @Test
    void aNewRowIsAQueuedRequestWithNoAttempts() {
        when(messages.findById(7L)).thenReturn(Optional.of(messageWithBody()));
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.empty());

        runner.queue(List.of(7L));

        ParsedEmail row = savedRow();
        assertThat(row.getStatus()).isEqualTo(ParseStatus.QUEUED);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getError()).isNull();
        assertThat(row.getParsedAt()).isNotNull();
    }

    @Test
    void aRowAlreadyQueuedIsCountedAndNotWrittenAgain() {
        when(messages.findById(7L)).thenReturn(Optional.of(messageWithBody()));
        ParsedEmail queued = new ParsedEmail();
        queued.setStatus(ParseStatus.QUEUED);
        queued.setAttempts(0);
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.of(queued));

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(7L));

        assertThat(outcome).isEqualTo(new EmailParseRunner.QueueOutcome(1, 0));
        verify(parsedEmails, never()).save(any());
    }

    @Test
    void readRowsAreSkippedWithoutAModelCallOrAWrite() {
        when(messages.findById(7L)).thenReturn(Optional.of(messageWithBody()));
        when(messages.findById(8L)).thenReturn(Optional.of(messageWithBody()));
        ParsedEmail parsed = new ParsedEmail();
        parsed.setStatus(ParseStatus.PARSED);
        ParsedEmail skipped = new ParsedEmail();
        skipped.setStatus(ParseStatus.SKIPPED);
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.of(parsed));
        when(parsedEmails.findByMailMessageId(8L)).thenReturn(Optional.of(skipped));

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(7L, 8L));

        assertThat(outcome).isEqualTo(new EmailParseRunner.QueueOutcome(0, 2));
        verify(parsedEmails, never()).save(any());
    }

    @Test
    void anIdNotOnThisDeskOrWithNoBodyIsSkipped() {
        when(messages.findById(7L)).thenReturn(Optional.empty());
        MailMessage blank = new MailMessage();
        blank.setBodyText("   ");
        when(messages.findById(8L)).thenReturn(Optional.of(blank));

        EmailParseRunner.QueueOutcome outcome = runner.queue(List.of(7L, 8L));

        assertThat(outcome).isEqualTo(new EmailParseRunner.QueueOutcome(0, 2));
        verify(parsedEmails, never()).save(any());
    }

    private static MailMessage messageWithBody() {
        MailMessage message = new MailMessage();
        message.setBodyText("25,000 MT wheat, Chornomorsk to Spain");
        return message;
    }

    private ParsedEmail savedRow() {
        ArgumentCaptor<ParsedEmail> captor = ArgumentCaptor.forClass(ParsedEmail.class);
        verify(parsedEmails).save(captor.capture());
        return captor.getValue();
    }
}
