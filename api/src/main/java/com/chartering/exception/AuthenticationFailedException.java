package com.chartering.exception;

/**
 * Wrong credentials, or too many attempts. Carries a message safe to show the user: it never
 * says which half of the credential was wrong.
 */
public class AuthenticationFailedException extends RuntimeException {
    public AuthenticationFailedException(String message) {
        super(message);
    }
}
