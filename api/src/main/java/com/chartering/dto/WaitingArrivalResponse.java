package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One arrival the sweep has yet to read, as a row of the Intake tab's "waiting" list.
 *
 * <p>Built by constructor expressions in the two repository queries, not from entities, so
 * the list never loads a body. The same record serves both doors: {@code source} says which
 * table {@code id} is in, and a field that does not apply to that door is simply null and
 * therefore absent from the JSON. A mail row has no {@code boardName}; a board post has no
 * {@code fromAddress} or {@code companyName}, because nothing on the page names a sender the
 * way an envelope does.
 *
 * <p>Two constructors, not one, and that is deliberate. A mail row has no board name, and
 * passing a typed {@code null} for it in a JPQL constructor expression leans on Hibernate
 * inferring a type from nothing; the shorter constructor lets the mail query stay plain.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaitingArrivalResponse(
        String source,
        Long id,
        LocalDateTime receivedAt,
        String fromName,
        String fromAddress,
        String companyName,
        String subject,
        String preview,
        String boardName) {

    /** The mail door: no board name. */
    public WaitingArrivalResponse(String source, Long id, LocalDateTime receivedAt,
                                  String fromName, String fromAddress, String companyName,
                                  String subject, String preview) {
        this(source, id, receivedAt, fromName, fromAddress, companyName, subject, preview, null);
    }
}
