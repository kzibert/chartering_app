package com.chartering.service.mail;

import com.chartering.model.Tenant;
import com.chartering.tenancy.TenantContext;

/**
 * Whose the mailbox in the environment is.
 *
 * <p>IMAP, SMTP and the Brevo key are configured once per deployment ({@code IMAP_*},
 * {@code MAIL_*}, {@code BREVO_API_KEY}), and a deployment used to be one desk. With several
 * desks on it, that one mailbox is the default desk's - the desk every existing message
 * already belongs to - and every other desk has no mailbox until it is given one. Reporting it
 * as missing, through the same "missing settings" lists the screens already explain, is what
 * keeps desk 2 from syncing desk 1's inbox or sending a circular as desk 1.
 *
 * <p>Work with no desk on the thread (the poller) is the deployment acting for its own
 * mailbox, and is allowed.
 */
public final class EnvironmentMailbox {

    /** Worded as the other entries of a missing-settings list are: the thing still needed. */
    public static final String NOT_THIS_DESK =
            "a mailbox for this desk (the server's mailbox belongs to the default desk)";

    private EnvironmentMailbox() {
    }

    public static boolean belongsToCurrentDesk() {
        return TenantContext.current().map(id -> id == Tenant.DEFAULT_ID).orElse(true);
    }
}
