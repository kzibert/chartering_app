package com.chartering.exception;

/**
 * No password was set on the server, so no login can succeed. Distinct from a wrong password
 * on purpose: this one is the operator's problem, not the user's, and saying so at the login
 * screen is what stops it looking like a forgotten password.
 */
public class AuthNotConfiguredException extends RuntimeException {
    public AuthNotConfiguredException(String message) {
        super(message);
    }
}
