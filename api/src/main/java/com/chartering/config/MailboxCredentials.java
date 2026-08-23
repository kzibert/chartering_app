package com.chartering.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.stereotype.Component;

/**
 * Which account the mailbox is read with: the IMAP credentials if they were given, the SMTP
 * ones otherwise. The usual case is one mailbox both read and written by the same account,
 * so falling back means one pair to configure instead of two.
 *
 * <p><b>Why this is a bean and not a placeholder default.</b> application.yml expresses the
 * same intent as {@code ${IMAP_USERNAME:${MAIL_USERNAME:}}}, and that works right up until
 * something defines {@code IMAP_USERNAME} as an <em>empty</em> string — because Spring
 * resolves an empty value as a value, not as absent, so the default never fires and the app
 * goes looking for a mailbox with no username.
 *
 * <p>Empty is not an exotic case: it is what a deployment platform hands over for a variable
 * someone left blank in a pasted {@code .env} block, and what Kubernetes and Heroku do too.
 * docker-compose papers over it — {@code ${IMAP_USERNAME:-${MAIL_USERNAME:-}}} treats empty
 * and unset alike — which is exactly the problem: the fallback worked only as long as
 * compose was the thing starting the app. Deciding it here means it holds wherever the app
 * runs, and blank is treated as "not given" throughout.
 */
@Component
@RequiredArgsConstructor
public class MailboxCredentials {

    private final MailboxProperties mailbox;
    private final MailProperties smtp;

    /** IMAP username, or the SMTP one when it is absent or blank. Never blank — null instead. */
    public String username() {
        return firstPresent(mailbox.getUsername(), smtp.getUsername());
    }

    /** IMAP password, or the SMTP one when it is absent or blank. */
    public String password() {
        return firstPresent(mailbox.getPassword(), smtp.getPassword());
    }

    /** Whether a usable pair exists at all — what the "is the mailbox set up" check asks. */
    public boolean arePresent() {
        return username() != null && password() != null;
    }

    private static String firstPresent(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        if (fallback != null && !fallback.isBlank()) return fallback;
        return null;
    }
}
