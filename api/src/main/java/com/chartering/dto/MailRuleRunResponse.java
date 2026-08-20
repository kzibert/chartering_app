package com.chartering.dto;

/**
 * What re-running the rules over already-synced mail actually did.
 *
 * <p>Reported rather than done silently because it is a bulk move: "evaluated 3,412, filed
 * 61" is the difference between a rule that works and a rule that matched nothing, and the
 * user is the only one who can tell which was intended.
 */
public record MailRuleRunResponse(
        int evaluated,
        /** Messages that changed folder as a result. */
        int filed,
        /** Messages a rule also marked read. */
        int markedRead) {
}
