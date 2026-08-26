package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A cargo as it is written.
 *
 * <p>Only the commodity is required. That is deliberate and it is the point of the whole
 * table: the first email about a cargo says what it is and often nothing else that can be
 * relied on, and a form that refuses to save until it has a laycan and a load port is a form
 * that sends the broker back to a notebook.
 *
 * <p>{@code quantityMin} / {@code quantityMax} may be left out. The service fills them from
 * the quantity and a percentage tolerance when it can read one, and leaves them alone when
 * the caller sent them — a broker overriding a range the arithmetic got wrong has to win.
 */
@Data
public class CargoRequest {

    @NotBlank(message = "commodity is required")
    private String commodity;

    /** One of {@code CargoStatus}; defaults to OPEN when absent. */
    private String status;
    private String statusNote;

    private BigDecimal stowageFactor;

    private BigDecimal quantity;
    private String quantityUnit;
    private String quantityTolerance;
    private BigDecimal quantityMin;
    private BigDecimal quantityMax;

    private Long loadPortId;
    private String loadPortText;
    private Long loadAreaId;

    private Long dischargePortId;
    private String dischargePortText;
    private Long dischargeAreaId;

    private LocalDate laycanFrom;
    private LocalDate laycanTo;
    private String laycanText;

    private BigDecimal maxDraft;
    private BigDecimal minDwt;
    private BigDecimal maxDwt;
    private Short maxAgeYears;
    private Boolean requiresGeared;
    private Boolean requiresGrainFitted;
    private Boolean requiresImoFitted;

    private String freightIdea;
    private String commission;
    private String terms;
    private String loadRate;
    private String dischargeRate;

    private Long chartererCompanyId;
    private Long brokerCompanyId;
    private Long brokerPersonId;

    /**
     * Where this came from, for the parser that lands later. A cargo created against a
     * message is marked {@code fromMail} by the service — the caller does not get to claim
     * it separately, because the two would drift apart the first time one was sent without
     * the other.
     */
    private Long sourceMailMessageId;
    private OffsetDateTime receivedAt;

    private String notes;
}
