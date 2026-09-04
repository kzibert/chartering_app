package com.chartering.service.mail;

import com.chartering.config.MailCampaignProperties;
import com.chartering.exception.MailSendFailedException;
import com.chartering.model.MailMessage;
import com.chartering.model.MailReply;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.MailReplyRepository;
import com.chartering.dto.MailReplyRequest;
import com.chartering.service.EmailFooterService;
import com.chartering.service.HtmlSanitizer;
import com.chartering.service.MailTemplateService;
import com.chartering.service.SettingsService;
import com.chartering.service.SettingsService.CirculationSettings;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Answering a message, and what happens when the mail server says no.
 *
 * <p>Both cases here came out of one bug report — "a 502 when I send a reply" — where the
 * status code was the only thing anybody could see. Neither test is about the happy path
 * being pretty; they are about a refusal being traceable to a cause, and about not handing
 * the mail server an envelope it was always going to refuse.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailReplyServiceTest {

    @Mock private MailMessageRepository messages;
    @Mock private MailReplyRepository replies;
    @Mock private EmailFooterService footers;
    @Mock private SmtpTransport transport;
    @Mock private SmtpCircularSender smtp;
    @Mock private SettingsService settings;

    private MailCampaignProperties props;
    private MailReplyService service;
    private JavaMailSenderImpl sender;

    /** The last message handed to the transport, or null if the send never got that far. */
    private MimeMessage sent;

    /** What the transport does with it. Null throws nothing; anything else is thrown. */
    private RuntimeException refusal;

    private final CirculationSettings cfg = new CirculationSettings(
            CircularProvider.SMTP, "circulars@example.com", "Chartering Desk",
            "smtp.zoho.eu", 465, 0, 0, 200, 0);

    @BeforeEach
    void setUp() {
        props = new MailCampaignProperties();
        props.setEnabled(true);

        sender = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage message) {
                sent = message;
                if (refusal != null) {
                    throw refusal;
                }
            }
        };
        sender.setHost("smtp.zoho.eu");
        sender.setPort(465);
        login("desk01");

        Mockito.when(settings.circulation()).thenReturn(cfg);
        Mockito.when(smtp.missingSettings(Mockito.any())).thenReturn(List.of());
        Mockito.when(transport.senderFor(Mockito.any())).thenReturn(sender);
        Mockito.when(replies.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));

        MailMessage original = new MailMessage();
        original.setId(7L);
        original.setMessageId("<abc@broker.example>");
        original.setFromAddress("broker@example.com");
        original.setFromName("A Broker");
        original.setBodyText("open Marmara");
        original.setReceivedAt(LocalDateTime.of(2026, 8, 31, 9, 30));
        Mockito.when(messages.findById(7L)).thenReturn(Optional.of(original));

        service = new MailReplyService(messages, replies, footers, new MailTemplateService(),
                new HtmlSanitizer(), transport, smtp, settings, props);
    }

    /**
     * The SMTP login, set in the one place it comes from in production. The transport's
     * username and the sender's are the same string there — both are read off the one
     * configured {@code JavaMailSender} — so a fixture that let them differ would be
     * testing a state that cannot happen.
     */
    private void login(String username) {
        sender.setUsername(username);
        Mockito.when(transport.username()).thenReturn(username);
    }

    private MailReplyRequest request() {
        MailReplyRequest req = new MailReplyRequest();
        req.setTo("broker@example.com");
        req.setSubject("Re: MV Something");
        req.setBodyHtml("<p>Noted, thanks.</p>");
        return req;
    }

    @Test
    void sendsFromTheCircularsAddressWhenTheSmtpLoginIsNotAnAddress() throws Exception {
        // A relay that authenticates on a bare login. Putting "desk01" in a From header
        // produces a message the server refuses, and the refusal talks about the envelope
        // rather than about the login, which is the wrong place to go looking.
        login("desk01");

        service.reply(7L, request());

        assertThat(sent.getFrom()[0].toString()).contains("circulars@example.com");
    }

    @Test
    void sendsFromTheMailboxWhenTheSmtpLoginIsTheMailbox() throws Exception {
        // The ordinary case, and the reason the username is preferred at all: the reply
        // comes back from the address the correspondent wrote to.
        login("chartering@example.com");

        service.reply(7L, request());

        assertThat(sent.getFrom()[0].toString()).contains("chartering@example.com");
        assertThat(sent.getHeader("In-Reply-To")[0]).isEqualTo("<abc@broker.example>");
    }

    @Test
    void aRefusalNamesTheAccountAndTheHostItWasRefusedBy() {
        login("chartering@example.com");
        refusal = new MailAuthenticationException("535 Authentication Failed");

        assertThatThrownBy(() -> service.reply(7L, request()))
                .isInstanceOf(MailSendFailedException.class)
                // The host is Settings' and the credentials are the environment's, so the
                // two can be made to disagree. Naming both is what makes that legible.
                .hasMessageContaining("smtp.zoho.eu:465")
                // The account the transport actually authenticated as, read back off it
                // rather than off the settings — those are the two halves that can disagree.
                .hasMessageContaining("chartering@example.com")
                .hasMessageContaining("535 Authentication Failed");
    }

    @Test
    void aReplyThatWasRefusedIsNotRecorded() {
        login("chartering@example.com");
        refusal = new MailAuthenticationException("535 Authentication Failed");

        assertThatThrownBy(() -> service.reply(7L, request()))
                .isInstanceOf(MailSendFailedException.class);

        Mockito.verify(replies, Mockito.never()).save(Mockito.any(MailReply.class));
    }
}
