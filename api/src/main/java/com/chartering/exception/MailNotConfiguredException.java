package com.chartering.exception;

/**
 * Sending was attempted while the SMTP settings are absent, disabled, or unusable.
 * Distinct from a bad request: the caller did nothing wrong, the server isn't ready.
 */
public class MailNotConfiguredException extends RuntimeException {

    public MailNotConfiguredException(String message) {
        super(message);
    }
}
