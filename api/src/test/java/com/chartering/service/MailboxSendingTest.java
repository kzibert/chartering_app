package com.chartering.service;

import com.chartering.dto.MailboxSendingResponse;
import com.chartering.model.MailServerFolder;
import com.chartering.model.MailSyncState;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.MailReplyRepository;
import com.chartering.repository.MailServerFolderRepository;
import com.chartering.repository.MailSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The day's outgoing volume as the mailbox itself reports it.
 *
 * <p>What is worth pinning here is not arithmetic but which question is being asked: the
 * Sent folder is found by its IMAP role rather than by its name, and a mailbox that has no
 * such folder must say so rather than answer zero.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailboxSendingTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 24, 0, 0);
    private static final LocalDateTime UNTIL = FROM.plusDays(1);

    @Mock
    private MailMessageRepository messages;
    @Mock
    private MailReplyRepository replies;
    @Mock
    private MailServerFolderRepository serverFolderRows;
    @Mock
    private MailSyncStateRepository syncState;

    @InjectMocks
    private MailboxService mailbox;

    @Test
    void readsTheDayOutOfWhicheverFolderTheServerCallsItsSentOne() {
        // The mailbox this was built against calls it "Отправленные". Matching on the word
        // "Sent" would find nothing at all, which is why the lookup is by SPECIAL-USE.
        MailServerFolder sent = new MailServerFolder();
        sent.setFullName("Отправленные");
        sent.setDisplayName("Отправленные");
        sent.setSpecialUse(MailServerFolder.SENT);
        Mockito.when(serverFolderRows.findFirstBySpecialUseAndPresentTrue(MailServerFolder.SENT))
                .thenReturn(Optional.of(sent));
        Mockito.when(messages.countByImapFolderAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(
                        "Отправленные", FROM, UNTIL))
                .thenReturn(9L);
        Mockito.when(replies.countBySentAtGreaterThanEqualAndSentAtLessThan(FROM, UNTIL))
                .thenReturn(2);

        MailSyncState state = new MailSyncState();
        state.setLastSyncAt(LocalDateTime.of(2026, 8, 24, 15, 35));
        Mockito.when(syncState.findById("Отправленные")).thenReturn(Optional.of(state));

        MailboxSendingResponse out = mailbox.sendingBetween(FROM, UNTIL);

        assertThat(out.sent()).isEqualTo(9);
        assertThat(out.sentFolder()).isEqualTo("Отправленные");
        assertThat(out.folderSyncedAt()).isEqualTo(state.getLastSyncAt());
        // Reported beside the folder figure, never added to it: the two replies are already
        // among the nine once that folder has synced.
        assertThat(out.replies()).isEqualTo(2);
    }

    @Test
    void aMailboxWithNoSentFolderSaysSoRatherThanReportingZero() {
        Mockito.when(serverFolderRows.findFirstBySpecialUseAndPresentTrue(MailServerFolder.SENT))
                .thenReturn(Optional.empty());
        Mockito.when(replies.countBySentAtGreaterThanEqualAndSentAtLessThan(FROM, UNTIL))
                .thenReturn(3);

        MailboxSendingResponse out = mailbox.sendingBetween(FROM, UNTIL);

        // Null, not 0. "Nothing was sent" and "nobody can tell you what was sent" are
        // different answers, and only one of them should reassure anybody.
        assertThat(out.sent()).isNull();
        assertThat(out.sentFolder()).isNull();
        assertThat(out.replies()).isEqualTo(3);
    }
}
