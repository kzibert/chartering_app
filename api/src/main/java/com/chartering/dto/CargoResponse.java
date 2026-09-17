package com.chartering.dto;

import com.chartering.model.SourceKind;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A cargo as the screens read it.
 *
 * <p>Every place is sent three ways — id, name and the raw text — because the caller needs
 * all three for different things: the id to re-open the edit form on the right dropdown
 * value, the name to print, and the text to show what the email actually said when no port
 * on file matched it. {@code @JsonInclude(NON_NULL)} keeps that from turning into a wall of
 * nulls: an absent field is absent from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CargoResponse(
        Long id,
        String status,
        String statusNote,
        String commodity,
        BigDecimal stowageFactor,

        BigDecimal quantity,
        String quantityUnit,
        String quantityTolerance,
        BigDecimal quantityMin,
        BigDecimal quantityMax,

        Long loadPortId,
        String loadPortName,
        String loadPortText,
        Long loadAreaId,
        String loadAreaCode,
        String loadAreaName,

        Long dischargePortId,
        String dischargePortName,
        String dischargePortText,
        Long dischargeAreaId,
        String dischargeAreaCode,
        String dischargeAreaName,

        LocalDate laycanFrom,
        LocalDate laycanTo,
        String laycanText,

        BigDecimal maxDraft,
        BigDecimal minDwt,
        BigDecimal maxDwt,
        Short maxAgeYears,
        Boolean requiresGeared,
        Boolean requiresGrainFitted,
        Boolean requiresImoFitted,

        /**
         * Days of ballast this cargo is worth, when it overrides the desk-wide setting.
         *
         * <p>Absent means it does not, which is almost every cargo - the form shows the
         * setting's own figure as the placeholder rather than copying it down, so a change
         * to the setting still moves every cargo that never disagreed with it.
         */
        Short maxBallastDays,

        String freightIdea,
        String commission,
        String terms,
        String loadRate,
        String dischargeRate,

        Long chartererCompanyId,
        String chartererCompanyName,
        Long brokerCompanyId,
        String brokerCompanyName,
        Long brokerPersonId,
        String brokerPersonName,

        /**
         * Typed, mailed, or read off a board. Was a {@code fromMail} boolean while there were
         * two answers; the Cargoes tab's Source filter is what wanted the third.
         */
        SourceKind sourceKind,
        Long sourceMailMessageId,

        /**
         * The board post it was read off, and the board's name — so the drawer can offer the
         * original to read exactly as it offers the original email.
         */
        Long sourceFeedItemId,
        String sourceFeedName,
        OffsetDateTime receivedAt,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,

        /**
         * When it last reached the desk: the newest arrival, else its received date, else when
         * it was entered. Absent only on the response to a create, before the row is re-read.
         */
        OffsetDateTime lastSentAt,

        /**
         * Who sent the newest arrival - the firm, else the person, else the address - and how
         * many different sending addresses it has come from, leaving out the desk's own. Filled on the list only; the drawer reads
         * every arrival instead. A cargo somebody typed has a count of 0 and no sender.
         */
        String lastSentBy,
        Integer senderCount) {
}
