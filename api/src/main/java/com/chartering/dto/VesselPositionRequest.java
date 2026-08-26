package com.chartering.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A position being recorded.
 *
 * <p>Only the vessel is required. "MV LADY LEYLA SPOT AT MARMARA" is a complete position as
 * far as the market is concerned and gives no dates at all; refusing to store it until
 * somebody invents a date would lose the report and gain nothing.
 */
@Data
public class VesselPositionRequest {

    @NotNull(message = "vesselId is required")
    private Long vesselId;

    /** One of {@code PositionStatus}; defaults to LIVE when absent. */
    private String status;

    private Long openPortId;
    private String openPortText;
    private Long openAreaId;

    private LocalDate openFrom;
    private LocalDate openTo;
    /** What was written: "01 / 02 SEPT", "SPOT", "PPT". */
    private String openText;

    private String lastCargo;
    private String cargoPreferences;

    private Long reportedByCompanyId;
    private Long reportedByPersonId;

    private Long sourceMailMessageId;
    /** When we were told. Defaults to now; a list read out of old mail should say so. */
    private OffsetDateTime reportedAt;

    private String notes;
}
