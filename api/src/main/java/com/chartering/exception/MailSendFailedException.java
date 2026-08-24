package com.chartering.exception;

/**
 * The mail server refused a message this app tried to send by hand — a reply from the
 * Mailbox tab.
 *
 * <p>Separate from {@link MailNotConfiguredException}, which says the send was never
 * attempted because something is missing. This one says it was attempted and the provider
 * said no, which is a different thing to read on a screen still holding the text you wrote:
 * nothing here is worth changing, and the same button may well work on the second press.
 *
 * <p>Circulars do not use it. A campaign classifies its failures per address and records
 * them against the run — see {@code CircularSendException} — because there is nobody
 * watching a two-hundred-message send to be told.
 */
public class MailSendFailedException extends RuntimeException {

    public MailSendFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
