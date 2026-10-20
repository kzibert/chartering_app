package com.chartering.service.parser;

import com.chartering.model.FeedItem;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Taking arrivals off the waiting list before the sweep reads them.
 *
 * <p>Kept apart from {@link EmailParseRunnerTest}, which builds the runner with nulls because
 * the scrubber it tests needs no collaborator. These tests need the repositories, so they are
 * mocks, and the assertions are about what gets written: the row's state, its link to the
 * arrival, and the count returned.
 */
class EmailParseRunnerLeaveOutTest {

    private final ParsedEmailRepository parsedEmails = mock(ParsedEmailRepository.class);
    private final MailMessageRepository messages = mock(MailMessageRepository.class);
    private final FeedItemRepository feedItems = mock(FeedItemRepository.class);
    private final EmailParseRunner runner = new EmailParseRunner(
            parsedEmails, messages, feedItems, null, null, new ObjectMapper(), null, null);

    @Test
    void leavesAWaitingMessageOutAsAnIgnoredRow() {
        MailMessage message = new MailMessage();
        when(messages.findById(7L)).thenReturn(Optional.of(message));

        int created = runner.leaveOut(List.of(7L), null, null);

        assertThat(created).isEqualTo(1);
        ParsedEmail row = savedRow();
        assertThat(row.getMailMessage()).isSameAs(message);
        assertThat(row.getFeedItem()).isNull();
        assertThat(row.getStatus()).isEqualTo(ParseStatus.IGNORED);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getParsedAt()).isNotNull();
        assertThat(row.getError()).isEqualTo("Left out of the queue — not to be read.");
    }

    @Test
    void leavesAWaitingBoardPostOutAsAnIgnoredRow() {
        FeedItem post = new FeedItem();
        when(feedItems.findById(9L)).thenReturn(Optional.of(post));

        int created = runner.leaveOut(null, List.of(9L), null);

        assertThat(created).isEqualTo(1);
        ParsedEmail row = savedRow();
        assertThat(row.getFeedItem()).isSameAs(post);
        assertThat(row.getMailMessage()).isNull();
        assertThat(row.getStatus()).isEqualTo(ParseStatus.IGNORED);
    }

    @Test
    void skipsAnArrivalThatAlreadyHasARowAndLeavesItAlone() {
        when(parsedEmails.findByMailMessageId(7L)).thenReturn(Optional.of(new ParsedEmail()));

        int created = runner.leaveOut(List.of(7L), null, null);

        // Already read or already left out: no longer waiting, and its verdict is not ours to change.
        assertThat(created).isZero();
        verify(parsedEmails, never()).save(any());
        verify(messages, never()).findById(7L);
    }

    @Test
    void skipsAnIdThatIsNotOnThisDesk() {
        // Another desk's id is not found through the tenant filter, exactly as an absent one is.
        when(messages.findById(8L)).thenReturn(Optional.empty());
        when(feedItems.findById(8L)).thenReturn(Optional.empty());

        int created = runner.leaveOut(List.of(8L), List.of(8L), null);

        assertThat(created).isZero();
        verify(parsedEmails, never()).save(any());
    }

    @Test
    void carriesTheNoteIntoTheErrorColumn() {
        when(feedItems.findById(9L)).thenReturn(Optional.of(new FeedItem()));

        runner.leaveOut(null, List.of(9L), "newsletter, no positions");

        assertThat(savedRow().getError()).isEqualTo("Left out of the queue — newsletter, no positions");
    }

    @Test
    void aBlankNoteGivesTheDefaultReason() {
        when(messages.findById(7L)).thenReturn(Optional.of(new MailMessage()));

        runner.leaveOut(List.of(7L), null, "   ");

        assertThat(savedRow().getError()).isEqualTo("Left out of the queue — not to be read.");
    }

    private ParsedEmail savedRow() {
        ArgumentCaptor<ParsedEmail> captor = ArgumentCaptor.forClass(ParsedEmail.class);
        verify(parsedEmails).save(captor.capture());
        return captor.getValue();
    }
}
