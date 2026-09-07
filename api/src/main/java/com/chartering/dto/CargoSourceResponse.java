package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * One arrival of a cargo: who told us, out of which email, and when.
 *
 * <p>What a merge is for. Three brokers working one charterer's enquiry become one cargo on
 * the Cargoes tab — otherwise it is three chances to offer the same ship — and this is the
 * list that keeps "who else is working this" answerable afterwards.
 *
 * <p>{@code fromAddress} is sent even when the company resolved, because a desk address and
 * the person who actually wrote are different facts and the second is often the one worth
 * having.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CargoSourceResponse(
        Long id,
        Long companyId,
        String companyName,
        Long personId,
        String personName,
        String fromAddress,
        Long mailMessageId,
        String mailSubject,
        OffsetDateTime reportedAt,
        String notes) {
}
