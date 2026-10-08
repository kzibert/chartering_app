package com.chartering.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * What a password must be, and how a temporary one is made.
 *
 * <p>Length and nothing else, which is current guidance (NIST SP 800-63B) rather than
 * laziness: composition rules ("one digit, one symbol") produce {@code Password1!} and do not
 * make guessing harder, while length does. The upper bound is BCrypt's - it reads 72 bytes
 * and silently ignores the rest, so a longer password would be accepted with its tail
 * meaning nothing.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    private static final int MAX_BYTES = 72;

    /** No 0/O, 1/l/I: a temporary password is read off a screen and typed by somebody else. */
    private static final char[] ALPHABET =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordPolicy() {
    }

    /** Throws {@link IllegalArgumentException} (a 400) naming what is wrong. */
    public static void check(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "The password must be at least " + MIN_LENGTH + " characters long.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("The password must be at most " + MAX_BYTES + " bytes long.");
        }
        if (username != null && password.trim().equalsIgnoreCase(username.trim())) {
            throw new IllegalArgumentException("The password must not be the username.");
        }
    }

    /** Sixteen characters from the unambiguous alphabet: about 93 bits, shown once. */
    public static String temporary() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
