package com.chartering.service.mail;

import com.chartering.config.AuthProperties;
import com.chartering.config.MailboxCredentials;
import com.chartering.config.MailboxProperties;
import com.chartering.model.AppUser;
import com.chartering.model.MailAccount;
import com.chartering.repository.AppUserRepository;
import com.chartering.repository.MailAccountRepository;
import com.chartering.security.CredentialCipher;
import com.chartering.tenancy.TenantContext;
import com.chartering.tenancy.TenantDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whose mailbox the code on this thread is using, and what it takes to reach it.
 *
 * <p>Two kinds, behind one shape. <b>The server's mailbox</b> is configured in the environment
 * ({@code IMAP_*}, {@code MAIL_*}) as it always was, and belongs to the account named by
 * {@code MAILBOX_OWNER}. <b>A personal mailbox</b> is a {@link MailAccount} row somebody saved
 * on the Settings tab, its password encrypted. Everything that reads or sends mail - the sync,
 * replies, circulars - asks here for {@link #current()} and works the same either way; only
 * where the credentials come from differs.
 *
 * <p>Somebody with neither has no mailbox, and the screens say so through the same
 * missing-settings lists they always used.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailAccounts {

    /** Worded as the other entries of a missing-settings list are: the thing still needed. */
    public static final String NO_MAILBOX = "your own mailbox (Settings > My mailbox)";

    private final MailboxProperties props;
    private final AuthProperties auth;
    private final MailboxCredentials environmentCredentials;
    private final MailAccountRepository accounts;
    private final AppUserRepository users;
    private final CredentialCipher cipher;
    private final TenantDirectory tenants;

    /**
     * Everything needed to read and send as one person.
     *
     * @param environment the server's mailbox, whose SMTP side is the configured transport
     *                    ({@code smtpHost}/{@code smtpPort} are then null and the circulation
     *                    settings decide, as before accounts existed)
     * @param password    null when a saved password could not be decrypted
     */
    public record Mailbox(Long tenantId, Long userId, boolean environment,
                          String address, String displayName,
                          boolean imapEnabled, String imapHost, int imapPort, boolean imapSsl, String folder,
                          String smtpHost, Integer smtpPort,
                          String username, String password) {
    }

    /** The mailbox of the person on this thread, if they have one. */
    public Optional<Mailbox> current() {
        return TenantContext.currentUser().flatMap(this::forUser);
    }

    public Optional<Mailbox> forUser(Long userId) {
        Optional<AppUser> envOwner = environmentOwner();
        if (envOwner.isPresent() && envOwner.get().getId().equals(userId)) {
            return Optional.of(environmentMailbox(envOwner.get()));
        }
        return accounts.findByUserId(userId).filter(MailAccount::isEnabled).map(this::toMailbox);
    }

    /** Whether {@code userId}'s mailbox is the server's, configured in the environment. */
    public boolean isEnvironmentOwner(Long userId) {
        return environmentOwner().map(u -> u.getId().equals(userId)).orElse(false);
    }

    /**
     * Every mailbox the poller reads: the server's, then each active desk's saved accounts.
     * The server's is included only while IMAP is switched on, as before.
     */
    public List<Mailbox> forSync() {
        List<Mailbox> out = new ArrayList<>();
        if (props.isEnabled()) {
            environmentOwner().ifPresentOrElse(
                    owner -> out.add(environmentMailbox(owner)),
                    () -> log.warn("IMAP is enabled but MAILBOX_OWNER '{}' is not an account; the server's "
                            + "mailbox is not read until it is", props.getOwner()));
        }
        tenants.forEachActive("Listing mailboxes", tenant -> accounts.findByEnabledTrue().stream()
                .filter(a -> !isEnvironmentOwner(a.getUserId()))
                .map(this::toMailbox)
                .forEach(out::add));
        return out;
    }

    /** The account the server's mailbox belongs to, if that account exists. */
    public Optional<AppUser> environmentOwner() {
        // Unset means the first account, which on an upgraded installation is the person who
        // was reading this mailbox before there were accounts.
        String owner = props.getOwner() == null || props.getOwner().isBlank() ? auth.getUsername() : props.getOwner();
        if (owner == null || owner.isBlank()) return Optional.empty();
        return users.findByUsernameIgnoreCase(owner.trim());
    }

    private Mailbox environmentMailbox(AppUser owner) {
        String username = environmentCredentials.username();
        return new Mailbox(owner.getTenant().getId(), owner.getId(), true,
                username, null,
                props.isEnabled(), props.getHost(), props.getPort(), props.isSsl(), props.getFolder(),
                null, null,
                username, environmentCredentials.password());
    }

    private Mailbox toMailbox(MailAccount a) {
        String password = null;
        try {
            password = cipher.decrypt(a.getPasswordEnc());
        } catch (IllegalStateException e) {
            log.warn("Mailbox of user {} cannot be used: {}", a.getUserId(), e.getMessage());
        }
        return new Mailbox(a.getTenantId(), a.getUserId(), false,
                a.getEmailAddress(), a.getDisplayName(),
                true, a.getImapHost(), a.getImapPort(), a.isImapSsl(), "INBOX",
                a.getSmtpHost(), a.getSmtpPort(),
                a.getEmailAddress(), password);
    }
}
