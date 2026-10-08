package com.chartering.service.mail;

import com.chartering.dto.BrevoAccountRequest;
import com.chartering.dto.BrevoAccountResponse;
import com.chartering.model.BrevoAccount;
import com.chartering.repository.BrevoAccountRepository;
import com.chartering.security.CredentialCipher;
import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Settings tab's Brevo key: the caller's own, and only ever the caller's - the rule
 * {@link MyMailAccountService} keeps for mailboxes, for the same reason. An administrator has
 * no screen for somebody else's key.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MyBrevoAccountService {

    private final BrevoAccountRepository accounts;
    private final BrevoAccounts brevoAccounts;
    private final CredentialCipher cipher;

    @Transactional(readOnly = true)
    public BrevoAccountResponse get() {
        Long me = TenantContext.requireUser();
        return accounts.findByUserId(me)
                .map(a -> new BrevoAccountResponse("PERSONAL", cipher.isAvailable(), a.getKeyHint(),
                        a.getSenderAddress(), a.getSenderName()))
                .orElseGet(() -> new BrevoAccountResponse(
                        brevoAccounts.environmentKeyFor(me) ? "SERVER" : "NONE",
                        cipher.isAvailable(), null, null, null));
    }

    @Transactional
    public BrevoAccountResponse save(BrevoAccountRequest req) {
        Long me = TenantContext.requireUser();
        BrevoAccount account = accounts.findByUserId(me).orElseGet(() -> {
            BrevoAccount fresh = new BrevoAccount();
            fresh.setUserId(me);
            return fresh;
        });
        String key = req.getApiKey() == null ? "" : req.getApiKey().trim();
        if (account.getId() == null && key.isEmpty()) {
            throw new IllegalArgumentException("The Brevo API key is required.");
        }
        if (!key.isEmpty()) {
            // Refused with the reason rather than stored in a form that only looks encrypted.
            account.setApiKeyEnc(cipher.encrypt(key));
            account.setKeyHint(hint(key));
        }
        account.setSenderAddress(blankToNull(req.getSenderAddress()));
        account.setSenderName(blankToNull(req.getSenderName()));
        accounts.save(account);
        log.info("Brevo account of user {} saved{}", me, key.isEmpty() ? "" : " with a new key");
        return get();
    }

    /** Stops sending through this key. Circulars already sent stay in History. */
    @Transactional
    public void delete() {
        accounts.findByUserId(TenantContext.requireUser()).ifPresent(accounts::delete);
    }

    /** The last four characters: enough to tell two keys apart, nothing to send with. */
    static String hint(String key) {
        return key.length() <= 4 ? "..." : "..." + key.substring(key.length() - 4);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
