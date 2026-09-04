package com.chartering.service.mail;

import com.chartering.config.MailCampaignProperties;
import com.chartering.dto.MailReplyRequest;
import com.chartering.exception.MailNotConfiguredException;
import com.chartering.model.MailMessage;
import com.chartering.model.MailReply;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.MailReplyRepository;
import com.chartering.service.EmailFooterService;
import com.chartering.service.HtmlSanitizer;
import com.chartering.service.MailTemplateService;
import com.chartering.service.SettingsService;
import com.chartering.service.SettingsService.CirculationSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which transport a reply leaves by, and what that choice must not disturb.
 *
 * <p>The interesting property is not that either route can send — it is that the route is a
 * deployment fact read from one variable, that it is independent of the circulars provider
 * in both directions, and that everything below it is unchanged. A reply routed through
 * Brevo has to be the same message, recorded the same way, or the two deployments are
 * quietly running two different features.
 *
 * <p>The SMTP path itself is not exercised here: it needs a live {@code JavaMailSenderImpl}
 * and its behaviour is not what this change touches. What is checked is that SMTP is what
 * happens by default and that Brevo is not consulted when it does.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailReplyRoutingTest {

    private static final String MAILBOX = "chartering@maritella.com";
    private static final String ANSWERED_ID = "<abc123@mail.gmail.com>";

    @Mock
    private MailMessageRepository messages;
    @Mock
    private MailReplyRepository replies;
    @Mock
    private EmailFooterService footers;
    @Mock
    private MailTemplateService templates;
    @Mock
    private HtmlSanitizer sanitizer;
    @Mock
    private SmtpTransport transport;
    @Mock
    private SmtpCircularSender smtp;
    @Mock
    private BrevoReplySender brevo;
    @Mock
    private SettingsService settings;

    /** Real, not a mock: it is a plain properties holder and the test's job is to set it. */
    private final MailCampaignProperties props = new MailCampaignProperties();

    private MailReplyService service;

    @BeforeEach
    void setUp() {
        service = new MailReplyService(messages, replies, footers, templates, sanitizer,
                transport, smtp, brevo, settings, props);
        props.setEnabled(true);
        // The circulars provider is BREVO throughout, precisely so that every assertion about
        // the reply route below is about the reply route and not about this.
        CirculationSettings cfg = new CirculationSettings(CircularProvider.BREVO,
                "desk@maritella.com", "Maritella Chartering Desk", "smtp.zoho.eu", 465,
                200, 800, 500, 60_000);
        when(settings.circulation()).thenReturn(cfg);
        when(transport.username()).thenReturn(MAILBOX);
        when(smtp.missingSettings(any())).thenReturn(List.of());
        when(brevo.missingSettings(any())).thenReturn(List.of());
        when(sanitizer.clean(anyString())).thenAnswer(i -> i.getArgument(0));
        when(templates.renderHtml(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        when(templates.htmlToText(anyString())).thenReturn("plain text");
        when(messages.findById(7L)).thenReturn(Optional.of(answeredMessage()));
        when(replies.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static MailMessage answeredMessage() {
        MailMessage m = new MailMessage();
        m.setId(7L);
        m.setFromAddress("ichart.mail@gmail.com");
        m.setFromName("iChart Chartering");
        m.setSubject("Test sub");
        m.setBodyText("Test");
        m.setMessageId(ANSWERED_ID);
        return m;
    }

    private static MailReplyRequest request() {
        MailReplyRequest r = new MailReplyRequest();
        r.setTo("ichart.mail@gmail.com");
        r.setSubject("Re: Test sub");
        r.setBodyHtml("<p>Noted, thanks.</p>");
        r.setIncludeOriginal(true);
        return r;
    }

    /**
     * Unset means SMTP. The variable exists for one unusual deployment; every other one must
     * carry on doing what it did before it was added, without being told to.
     */
    @Test
    void defaultsToTheMailboxEvenWhileCircularsUseBrevo() {
        assertThat(service.replyProvider()).isEqualTo(CircularProvider.SMTP);

        // No SMTP sender is configured on this mock, so the reply stops at that check — which
        // is itself the proof it went down the SMTP path and never looked at Brevo.
        assertThatThrownBy(() -> service.reply(7L, request()))
                .isInstanceOf(MailNotConfiguredException.class)
                .hasMessageContaining("No SMTP transport");
        verify(brevo, never()).send(any(), any(), any(), any(), any(), any(), any());
    }

    /** A value nothing recognises must not silently pick the route that cannot work. */
    @Test
    void anUnreadableValueFallsBackToTheMailbox() {
        props.setReplyProvider("brevoo");
        assertThat(service.replyProvider()).isEqualTo(CircularProvider.SMTP);
    }

    @Test
    void brevoSendsAsTheMailboxAndReferencesTheAnsweredMessage() {
        props.setReplyProvider("BREVO");
        when(brevo.send(any(), any(), any(), any(), any(), any(), any())).thenReturn("<brevo-99@maritella.com>");

        service.reply(7L, request());

        // The From is the mailbox, not the circulars address: that is what the correspondent
        // wrote to, and the only reason this route is acceptable at all.
        verify(brevo).send(eq(MAILBOX), eq("Maritella Chartering Desk"),
                eq("ichart.mail@gmail.com"), eq("Re: Test sub"),
                anyString(), eq("plain text"), eq(ANSWERED_ID));
    }

    /**
     * Brevo stamps its own Message-ID and files no copy anywhere, so the id it hands back is
     * the only one the sent message actually carries. Minting one here as the SMTP path does
     * would store an identifier that appears in no mail in the world.
     */
    @Test
    void brevosOwnMessageIdIsWhatGetsRecorded() {
        props.setReplyProvider("BREVO");
        when(brevo.send(any(), any(), any(), any(), any(), any(), any())).thenReturn("<brevo-99@maritella.com>");

        service.reply(7L, request());

        ArgumentCaptor<MailReply> saved = ArgumentCaptor.forClass(MailReply.class);
        verify(replies).save(saved.capture());
        assertThat(saved.getValue().getMessageId()).isEqualTo("<brevo-99@maritella.com>");
        assertThat(saved.getValue().getToAddress()).isEqualTo("ichart.mail@gmail.com");
        // The quoted original still travels: the route changes the transport, nothing above it.
        assertThat(saved.getValue().getBodyHtml()).contains("Noted, thanks.").contains("wrote:");
    }

    /** Missing settings are reported for the route in force, not the other one. */
    @Test
    void brevoRouteAsksBrevoWhatIsMissing() {
        props.setReplyProvider("BREVO");
        when(brevo.missingSettings(MAILBOX)).thenReturn(List.of("BREVO_API_KEY"));

        assertThatThrownBy(() -> service.reply(7L, request()))
                .isInstanceOf(MailNotConfiguredException.class)
                .hasMessageContaining("BREVO_API_KEY");
        verify(brevo, never()).send(any(), any(), any(), any(), any(), any(), any());
    }

    /** MAIL_ENABLED is the master switch and outranks the route entirely. */
    @Test
    void theMasterSwitchStillStopsEverything() {
        props.setReplyProvider("BREVO");
        props.setEnabled(false);

        assertThatThrownBy(() -> service.reply(7L, request()))
                .isInstanceOf(MailNotConfiguredException.class)
                .hasMessageContaining("MAIL_ENABLED");
        verify(brevo, never()).send(any(), any(), any(), any(), any(), any(), any());
    }
}
