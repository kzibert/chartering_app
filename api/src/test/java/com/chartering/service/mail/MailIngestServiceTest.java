package com.chartering.service.mail;

import com.chartering.model.MailMessage;
import com.chartering.repository.MailMessageRepository;
import com.chartering.service.MailRuleService;
import com.chartering.service.MailboxService;
import com.chartering.tenancy.TestLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a message looks like once it is written. The poison-pill case: Postgres refuses a NUL
 * character in text, so a message carrying one must be stored with it removed, not left to fail
 * the transaction for the whole batch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailIngestServiceTest {

    @Mock private MailMessageRepository messages;
    @Mock private MailboxService mailbox;
    @Mock private MailRuleService ruleService;

    private MailIngestService service;

    @BeforeEach
    void setUp() {
        TestLogin.asDefault();
        service = new MailIngestService(messages, mailbox, ruleService);
        when(ruleService.enabledRules()).thenReturn(List.of());
        when(ruleService.fileByRules(any(), any())).thenReturn(MailRuleService.Outcome.UNTOUCHED);
    }

    @AfterEach
    void tearDown() {
        TestLogin.clear();
    }

    @Test
    void shouldStripNulCharactersFromEveryTextField_beforeTheRowIsWritten() {
        MailIngestService.Incoming in = incoming(
                "Ka\u0000rl <karl@example.com>",
                "Hel\u0000lo",
                "plain\u0000 text",
                "<p>\u0000markup</p>",
                "snip\u0000pet",
                "attach\u0000.pdf",
                "to\u0000@example.com");

        service.store(List.of(in));

        MailMessage saved = savedMessage();
        assertThat(saved.getFromName()).isEqualTo("Karl <karl@example.com>");
        assertThat(saved.getSubject()).isEqualTo("Hello");
        assertThat(saved.getBodyText()).isEqualTo("plain text");
        assertThat(saved.getBodyHtml()).isEqualTo("<p>markup</p>");
        assertThat(saved.getSnippet()).isEqualTo("snippet");
        assertThat(saved.getAttachmentNames()).isEqualTo("attach.pdf");
        assertThat(saved.getToAddresses()).isEqualTo("to@example.com");
    }

    @Test
    void shouldLeaveNullFieldsNull_whenScrubbing() {
        MailIngestService.Incoming in = new MailIngestService.Incoming(
                "<clean-id@example.com>", 7L, 1L, "INBOX",
                "sender@example.com", null, null, null,
                null, null, LocalDateTime.of(2026, 10, 20, 9, 0),
                null, null, null,
                false, null, null, false);

        service.store(List.of(in));

        MailMessage saved = savedMessage();
        assertThat(saved.getFromName()).isNull();
        assertThat(saved.getSubject()).isNull();
        assertThat(saved.getBodyText()).isNull();
        assertThat(saved.getAttachmentNames()).isNull();
    }

    private MailMessage savedMessage() {
        ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
        verify(messages).save(captor.capture());
        return captor.getValue();
    }

    private static MailIngestService.Incoming incoming(String fromName, String subject, String bodyText,
                                                       String bodyHtml, String snippet,
                                                       String attachmentNames, String toAddresses) {
        return new MailIngestService.Incoming(
                "<msg-1@example.com>", 7L, 1L, "INBOX",
                "sender@example.com", fromName, toAddresses, null,
                subject, LocalDateTime.of(2026, 10, 20, 9, 0), LocalDateTime.of(2026, 10, 20, 9, 1),
                bodyText, bodyHtml, snippet,
                true, attachmentNames, 1024, false);
    }
}
