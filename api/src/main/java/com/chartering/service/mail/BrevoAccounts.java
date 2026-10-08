package com.chartering.service.mail;

import com.chartering.config.BrevoProperties;
import com.chartering.model.BrevoAccount;
import com.chartering.repository.BrevoAccountRepository;
import com.chartering.security.CredentialCipher;
import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Whose Brevo account the code on this thread is sending through - the Brevo half of what
 * {@link MailAccounts} is for mailboxes.
 *
 * <p>Two kinds, behind one shape. <b>A personal key</b> is a {@link BrevoAccount} row somebody
 * saved on Settings &gt; My mailbox, encrypted. <b>The server's key</b>, {@code BREVO_API_KEY},
 * belongs to the account that owns the server's mailbox ({@code MAILBOX_OWNER}) - the person
 * the deployment was set up for - and to nobody else. It used to be the whole default desk's,
 * which let a colleague spend its allowance and its reputation from their own login; that is
 * the thing per-person circulars (V33) exist to stop. A saved key wins over the server's for
 * its owner too, so the server's account can be swapped for a personal one without a restart.
 *
 * <p>Everything that talks to Brevo - circulars, Brevo-routed replies, the "sent today"
 * figures - asks here and nowhere else. Somebody with neither has no Brevo route, and the
 * screens say so through the missing-settings lists they already explain.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrevoAccounts {

    /** Worded as the other entries of a missing-settings list are: the thing still needed. */
    public static final String NO_KEY = "your own Brevo API key (Settings > My mailbox)";

    private final BrevoProperties props;
    private final BrevoAccountRepository accounts;
    private final MailAccounts mailAccounts;
    private final CredentialCipher cipher;

    /**
     * One person's route into Brevo.
     *
     * @param environment   the server's {@code BREVO_API_KEY}
     * @param senderAddress the verified sender to send as, or null to send as the mailbox
     */
    public record Brevo(Long userId, boolean environment, String apiKey,
                        String senderAddress, String senderName) {
    }

    /** The Brevo account of the person on this thread, if they have one that can be used. */
    public Optional<Brevo> current() {
        return TenantContext.currentUser().flatMap(this::forUser);
    }

    public Optional<Brevo> forUser(Long userId) {
        Optional<BrevoAccount> saved = accounts.findByUserId(userId);
        if (saved.isPresent()) {
            return decrypt(saved.get());
        }
        if (environmentKeyFor(userId)) {
            return Optional.of(new Brevo(userId, true, props.getApiKey().trim(), null, null));
        }
        return Optional.empty();
    }

    /** Whether the server's key is this person's - shown, not editable, on their card. */
    public boolean environmentKeyFor(Long userId) {
        return isSet(props.getApiKey()) && mailAccounts.isEnvironmentOwner(userId);
    }

    /**
     * A key that cannot be decrypted (CREDENTIALS_KEY changed or unset since it was saved) is
     * no key: sending with it would be a 401 from Brevo that reads as "your key is wrong" when
     * the truth is that this server can no longer read it.
     */
    private Optional<Brevo> decrypt(BrevoAccount a) {
        try {
            return Optional.of(new Brevo(a.getUserId(), false, cipher.decrypt(a.getApiKeyEnc()),
                    a.getSenderAddress(), a.getSenderName()));
        } catch (IllegalStateException e) {
            log.warn("Brevo key of user {} cannot be used: {}", a.getUserId(), e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
