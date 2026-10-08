package com.chartering.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts the secrets people give this application to act for them - today, the password of
 * their own mailbox - so the database holds ciphertext, and a copy of the database (a backup,
 * a restored dump on a laptop) is not a copy of everybody's mail password.
 *
 * <p>AES-256-GCM: authenticated, so a value tampered with in the table fails to decrypt
 * rather than decrypting to something else. A fresh 96-bit nonce per value, stored in front
 * of it. The key is {@code CREDENTIALS_KEY} hashed to 256 bits, so any long random string
 * works and nobody has to produce exactly 32 bytes of base64.
 *
 * <p><b>No key, no storing.</b> Without one the application still runs; saving a mailbox
 * password is refused with the reason, which is the safe direction - the alternative is
 * plaintext that looks encrypted. Losing the key loses the stored passwords (everyone types
 * theirs again) and nothing else, which is why it is a separate secret from
 * {@code JWT_SECRET}: rotating that one should log people out, not erase their mailboxes.
 */
@Component
@Slf4j
public class CredentialCipher {

    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public CredentialCipher(@Value("${chartering.credentials.key:}") String secret) {
        this.key = secret == null || secret.isBlank() ? null : derive(secret);
        if (key == null) {
            log.info("CREDENTIALS_KEY is not set: personal mailbox passwords cannot be stored. "
                    + "The server's own mailbox (IMAP_*/MAIL_*) is unaffected.");
        }
    }

    public boolean isAvailable() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a credential", e);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Not a credential this application stored");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
            byte[] plain = cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A different CREDENTIALS_KEY from the one it was saved under, or a damaged row.
            throw new IllegalStateException("A stored credential could not be decrypted - was CREDENTIALS_KEY changed?", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "This server cannot store passwords until CREDENTIALS_KEY is set. Ask the administrator.");
        }
    }

    private static SecretKey derive(String secret) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
