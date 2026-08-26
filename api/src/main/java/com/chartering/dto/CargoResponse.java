package com.chartering.dto;

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

        boolean fromMail,
        Long sourceMailMessageId,
        OffsetDateTime receivedAt,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
