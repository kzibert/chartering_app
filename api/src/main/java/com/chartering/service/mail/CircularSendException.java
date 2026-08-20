package com.chartering.service.mail;

/**
 * One message failed to leave, classified by what the campaign should do about it.
 *
 * <p>The classification is the point. SMTP says it with a reply code and Brevo says it with
 * an HTTP status, but the campaign only ever needs to know three things: try again, give up
 * on this address, or stop the whole run. Translating each provider's vocabulary into this
 * at the edge is what lets the run loop stay provider-agnostic.
 */
public class CircularSendException extends RuntimeException {

    public enum Kind {
        /** Throttling, a timeout, a 4xx SMTP reply — worth another attempt after a backoff. */
        TRANSIENT,
        /** The address or the message is refused outright. Retrying only wastes quota. */
        PERMANENT,
        /**
         * The credentials were rejected. Never retried and never merely recorded: every
         * remaining message would fail identically, and repeated auth failures are
         * themselves a lockout trigger.
         */
        AUTH
    }

    private final Kind kind;

    private CircularSendException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** True when another attempt is pointless — the caller records the failure and moves on. */
    public boolean permanent() {
        return kind != Kind.TRANSIENT;
    }

    public boolean auth() {
        return kind == Kind.AUTH;
    }

    public static CircularSendException transientFailure(String message, Throwable cause) {
        return new CircularSendException(Kind.TRANSIENT, message, cause);
    }

    public static CircularSendException permanent(String message, Throwable cause) {
        return new CircularSendException(Kind.PERMANENT, message, cause);
    }

    public static CircularSendException auth(String message, Throwable cause) {
        return new CircularSendException(Kind.AUTH, message, cause);
    }

    /**
     * The innermost cause's message, tidied for a log line.
     *
     * <p>Mail stacks bury the useful sentence several wrappers deep — the outer exception
     * usually says only "Mail server connection failed", and the reply code that explains
     * why is on the cause. Shared with the campaign log so a failure reads the same wherever
     * it surfaces.
     */
    public static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) {
            return cur.getClass().getSimpleName();
        }
        String clean = msg.replaceAll("\\s+", " ").trim();
        // Some exceptions carry a bare token as their message - UnknownHostException's is
        // just the hostname, which reads as nonsense in a log. Qualify those with the type.
        return clean.contains(" ") ? clean : cur.getClass().getSimpleName() + ": " + clean;
    }
}
