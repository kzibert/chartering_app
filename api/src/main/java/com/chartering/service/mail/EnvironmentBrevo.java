package com.chartering.service.mail;

import com.chartering.model.Tenant;
import com.chartering.tenancy.TenantContext;

/**
 * Whose the Brevo account in the environment is.
 *
 * <p>{@code BREVO_API_KEY} is configured once per deployment, and a deployment used to be one
 * desk. With several desks on it, that account is the default desk's - its sends, its daily
 * allowance and its figures are that desk's business - and every other desk has no Brevo route
 * until it is given one. Reporting it as missing, through the same "missing settings" lists the
 * screens already explain, is what keeps desk 2 from spending desk 1's allowance.
 *
 * <p>Mailboxes are not decided here: each person has their own ({@link MailAccounts}).
 */
public final class EnvironmentBrevo {

    /** Worded as the other entries of a missing-settings list are: the thing still needed. */
    public static final String NOT_THIS_DESK =
            "a Brevo account for this desk (the server's Brevo key belongs to the default desk)";

    private EnvironmentBrevo() {
    }

    public static boolean belongsToCurrentDesk() {
        return TenantContext.current().map(id -> id == Tenant.DEFAULT_ID).orElse(true);
    }
}
