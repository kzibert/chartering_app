package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContactResponse(
        Long id,
        Long personId,
        String personName,
        /**
         * The person behind this address has left the company, so it is off circulations —
         * every one of their addresses is, and none of the address's own flags says so.
         * Carried on the contact rather than looked up per row, because a contact list that
         * cannot show why a row will never be mailed is a list that gets re-collected.
         */
        boolean personLeft,
        String title,
        /**
         * The position the person behind this address holds at the company. Read off the
         * person, never stored here: one human's mobile and two mailboxes all carry the
         * same position, and three copies of it would be three chances to disagree.
         *
         * <p>Null for a company-wide address, which has no person to hold a job.
         */
        String jobTitle,
        /**
         * The greeting to actually use for this address: the contact's own when it has one,
         * otherwise the person's. Everything downstream reads this one — the circulation
         * list builder, the WhatsApp link, the contact row — so a company-wide address with
         * a greeting typed on it is greeted by it everywhere, and one without stays null and
         * falls through to the general "Sirs" at merge time.
         */
        String greetingName,
        /**
         * The override as stored, with no fallback applied — null unless somebody typed one.
         * Only the edit form wants this: prefilling it from {@link #greetingName} would show
         * the person's greeting in the field and then pin that copy onto the contact on the
         * next save, quietly severing it from the person it was inherited from.
         */
        String ownGreetingName,
        /**
         * True when this address belongs to the company itself rather than to any person —
         * a chartering@ or ops@ desk. Derived, not stored; it is only {@code personId == null
         * && companyId != null} spelled out, so the UI can label the row without every caller
         * re-deriving the same condition.
         */
        boolean companyWide,
        Long companyId,
        String companyName,
        String contactKind,
        String contactValue,
        String notes,
        boolean confirmed,
        OffsetDateTime confirmedAt,
        String confirmedBy,
        String confirmNotes,
        boolean banned,
        boolean legacy,
        boolean main,
        boolean working,
        /** flagged for use in circulations — see RecipientSelectionService for the rule */
        boolean circ,
        /**
         * flagged never to be circulated to. Distinct from {@code working}: the address is
         * fine and still the one to write to by hand, it is only bulk mail it is kept out of.
         */
        boolean noCirc,
        /**
         * this number is on WhatsApp, as confirmed by hand from the wa.me check. Phone
         * contacts only — see {@code ContactService#setHasWhatsapp}.
         */
        boolean hasWhatsapp) {
}
