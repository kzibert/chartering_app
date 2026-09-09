package com.chartering.dto;

import com.chartering.model.IntakeItemKind;
import com.chartering.model.IntakeItemStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One row of the review queue.
 *
 * <p>Carries the email it came out of — sender, subject, when it arrived — because that is
 * how a broker recognises what they are being asked about. "PACIFIC DAWN, DWT disagrees" is
 * a question about a spreadsheet; "PACIFIC DAWN, DWT disagrees, from Interscan's Tuesday
 * list" is a question about the market, and the second one can be answered from memory.
 *
 * @param payload the kind-specific detail — {@code IntakePayloads} — passed through as JSON
 *                rather than flattened into fields here. The three kinds share nothing but
 *                an id, and a response record with every field of all three would be mostly
 *                nulls on every row
 * @param counts  a short summary of the payload for the list ("3 fields differ"), so the
 *                table is readable without opening each drawer
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntakeItemResponse(
        Long id,
        IntakeItemKind kind,
        IntakeItemStatus status,
        String subjectLabel,
        String summary,
        Long vesselId,
        Long cargoId,
        Long mailMessageId,
        String fromAddress,
        String fromName,
        /**
         * The company the sender was resolved to by the mail sync. What makes "link this
         * broker to the ship" possible without anybody typing a company name.
         */
        Long senderCompanyId,
        String senderCompanyName,
        String mailSubject,
        LocalDateTime receivedAt,
        JsonNode payload,
        /**
         * What an outside source found, on the detail call only. Absent from the list: it
         * carries every candidate a search returned and a page of rows would be mostly that.
         */
        VesselLookupResponse lookup,
        /**
         * Every email that raised this item, newest first — detail call only.
         *
         * <p>A question about a hull is asked by however many emails mention her, and they
         * are merged into one item rather than one row each. This is what lets the drawer
         * offer each original to read and each sending firm to attach. Always at least one:
         * the arrival that first raised it is a source like any other.
         */
        List<IntakeItemSourceResponse> sources,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        String resolvedBy,
        String resolutionNote) {
}
