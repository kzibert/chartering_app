package com.chartering.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case these exist for: a platform that hands over an environment variable someone left
 * blank. Compose used to absorb that before the app ever saw it; nothing does on Render, and
 * an empty string is a value as far as a Spring placeholder default is concerned.
 */
class MailboxCredentialsTest {

    private MailboxCredentials credentials(String imapUser, String imapPass,
                                           String smtpUser, String smtpPass) {
        MailboxProperties mailbox = new MailboxProperties();
        mailbox.setUsername(imapUser);
        mailbox.setPassword(imapPass);
        MailProperties smtp = new MailProperties();
        smtp.setUsername(smtpUser);
        smtp.setPassword(smtpPass);
        return new MailboxCredentials(mailbox, smtp);
    }

    @Test
    void anEmptyImapUsernameFallsBackToTheSmtpOne() {
        // Exactly what a pasted .env with "IMAP_USERNAME=" produces.
        var c = credentials("", "", "desk@example.com", "smtp-secret");

        assertThat(c.username()).isEqualTo("desk@example.com");
        assertThat(c.password()).isEqualTo("smtp-secret");
        assertThat(c.arePresent()).isTrue();
    }

    @Test
    void anAbsentImapUsernameFallsBackToo() {
        var c = credentials(null, null, "desk@example.com", "smtp-secret");

        assertThat(c.username()).isEqualTo("desk@example.com");
        assertThat(c.arePresent()).isTrue();
    }

    @Test
    void whitespaceCountsAsAbsent() {
        var c = credentials("   ", "\t", "desk@example.com", "smtp-secret");

        assertThat(c.username()).isEqualTo("desk@example.com");
        assertThat(c.password()).isEqualTo("smtp-secret");
    }

    @Test
    void aRealImapUsernameWinsOverTheSmtpOne() {
        var c = credentials("reader@example.com", "imap-secret", "desk@example.com", "smtp-secret");

        assertThat(c.username()).isEqualTo("reader@example.com");
        assertThat(c.password()).isEqualTo("imap-secret");
    }

    @Test
    void theTwoFallBackIndependently() {
        // One mailbox, one password, but a separate reader alias for the username.
        var c = credentials("reader@example.com", "", "desk@example.com", "smtp-secret");

        assertThat(c.username()).isEqualTo("reader@example.com");
        assertThat(c.password()).isEqualTo("smtp-secret");
    }

    @Test
    void nothingConfiguredAnywhereReportsAbsentRatherThanBlank() {
        var c = credentials("", "", "", "");

        assertThat(c.username()).isNull();
        assertThat(c.password()).isNull();
        assertThat(c.arePresent()).isFalse();
    }
}
