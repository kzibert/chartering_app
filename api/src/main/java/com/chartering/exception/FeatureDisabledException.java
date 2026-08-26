package com.chartering.exception;

/**
 * A feature that is not part of this deployment was asked for.
 *
 * <p>Answered with 404 rather than 403 or 503, and the difference matters. 403 would say
 * "you may not", which invites someone to go looking for the permission that would let them;
 * 503 would say "not yet, try later", which is what {@link MailNotConfiguredException} means
 * and is a promise this cannot keep. The endpoint genuinely is not here — the switch is off
 * in this environment on purpose — and "not found" is the honest answer.
 *
 * <p>It is a backstop, not the mechanism: the UI hides a disabled feature's tab entirely,
 * having asked the feature's own status endpoint, which always answers.
 */
public class FeatureDisabledException extends RuntimeException {

    public FeatureDisabledException(String message) {
        super(message);
    }
}
