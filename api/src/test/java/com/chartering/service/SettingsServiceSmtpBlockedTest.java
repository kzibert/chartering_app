package com.chartering.service;

import com.chartering.config.BrevoProperties;
import com.chartering.config.MailCampaignProperties;
import com.chartering.model.AppSetting;
import com.chartering.service.mail.BrevoAccounts;
import com.chartering.service.mail.CircularProvider;
import com.chartering.service.mail.MailAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The circulars provider on a host that cannot open SMTP: the desk's stored row is shared with
 * an office instance that can, so here it is overruled rather than obeyed or overwritten.
 */
class SettingsServiceSmtpBlockedTest {

    private SettingsStore store;
    private MailCampaignProperties props;
    private SettingsService settings;

    @BeforeEach
    void setUp() {
        store = mock(SettingsStore.class);
        props = new MailCampaignProperties();
        settings = new SettingsService(store, mock(MailAccounts.class), mock(BrevoAccounts.class), props,
                new BrevoProperties(), mock(JavaMailSender.class));
        AppSetting smtp = new AppSetting();
        smtp.setKey(SettingsService.PROVIDER);
        smtp.setValue(CircularProvider.SMTP.name());
        when(store.findById(SettingsService.PROVIDER)).thenReturn(Optional.of(smtp));
    }

    @Test
    void followsTheDesksChoice_whereSmtpIsReachable() {
        assertThat(settings.provider()).isEqualTo(CircularProvider.SMTP);
    }

    @Test
    void sendsThroughBrevo_whenSmtpIsBlocked() {
        props.setSmtpBlocked(true);

        assertThat(settings.provider()).isEqualTo(CircularProvider.BREVO);
    }

    @Test
    void refusesToStoreSmtp_whenSmtpIsBlocked() {
        props.setSmtpBlocked(true);

        assertThatThrownBy(() -> settings.updateProvider(CircularProvider.SMTP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SMTP_BLOCKED");
        verify(store, never()).save(any());
    }
}
