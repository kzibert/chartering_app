package com.chartering.service.mail;

import com.chartering.config.MailboxProperties;
import com.chartering.dto.MailAccountRequest;
import com.chartering.dto.MailAccountResponse;
import com.chartering.model.MailAccount;
import com.chartering.repository.MailAccountRepository;
import com.chartering.security.CredentialCipher;
import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Settings tab's "My mailbox": the caller's own, and only ever the caller's. There is no
 * way to set somebody else's mailbox here, and an administrator has none either - giving
 * someone your mail password is not something an Admin screen should make normal.
 *
 * <p>The server's mailbox (the environment's) belongs to one account; for that person this
 * shows it read-only, since its credentials live where the deployment keeps them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MyMailAccountService {

    private final MailAccountRepository accounts;
    private final MailAccounts mailAccounts;
    private final MailboxProperties serverMailbox;
    private final CredentialCipher cipher;

    @Transactional(readOnly = true)
    public MailAccountResponse get() {
        Long me = TenantContext.requireUser();
        if (mailAccounts.isEnvironmentOwner(me)) {
            MailAccounts.Mailbox m = mailAccounts.forUser(me).orElseThrow();
            return new MailAccountResponse("SERVER", cipher.isAvailable(), m.address(), null,
                    m.imapHost(), m.imapPort(), m.imapSsl(), null, null,
                    serverMailbox.isEnabled(), m.password() != null && !m.password().isBlank());
        }
        return accounts.findByUserId(me)
                .map(a -> new MailAccountResponse("PERSONAL", cipher.isAvailable(), a.getEmailAddress(),
                        a.getDisplayName(), a.getImapHost(), a.getImapPort(), a.isImapSsl(),
                        a.getSmtpHost(), a.getSmtpPort(), a.isEnabled(), true))
                .orElse(new MailAccountResponse("NONE", cipher.isAvailable(),
                        null, null, null, null, null, null, null, null, false));
    }

    @Transactional
    public MailAccountResponse save(MailAccountRequest req) {
        Long me = TenantContext.requireUser();
        if (mailAccounts.isEnvironmentOwner(me)) {
            throw new IllegalArgumentException("Your mailbox is the server's own, configured in its environment "
                    + "(IMAP_* and MAIL_*). Change it there.");
        }
        MailAccount account = accounts.findByUserId(me).orElseGet(() -> {
            MailAccount fresh = new MailAccount();
            fresh.setUserId(me);
            return fresh;
        });
        boolean newPassword = req.getPassword() != null && !req.getPassword().isBlank();
        if (account.getId() == null && !newPassword) {
            throw new IllegalArgumentException("The mailbox password is required.");
        }
        if (newPassword) {
            // Refused with the reason rather than stored in a form that only looks encrypted.
            account.setPasswordEnc(cipher.encrypt(req.getPassword()));
        }
        account.setEmailAddress(req.getEmailAddress().trim());
        account.setDisplayName(blankToNull(req.getDisplayName()));
        account.setImapHost(req.getImapHost().trim());
        account.setImapPort(req.getImapPort());
        account.setImapSsl(req.isImapSsl());
        account.setSmtpHost(req.getSmtpHost().trim());
        account.setSmtpPort(req.getSmtpPort());
        account.setEnabled(req.isEnabled());
        accounts.save(account);
        log.info("Mailbox of user {} saved ({}){}", me, account.getEmailAddress(),
                newPassword ? " with a new password" : "");
        return get();
    }

    /** Stops reading and sending as this mailbox. The mail already synced stays on the tab. */
    @Transactional
    public void delete() {
        accounts.findByUserId(TenantContext.requireUser()).ifPresent(accounts::delete);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
