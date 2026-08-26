package com.chartering.dto;

import com.chartering.model.AnalysisLabel;
import com.chartering.model.AnalysisStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One row of the corpus, without the email in it.
 *
 * <p>The body is the largest column in the table and the list never shows more than the
 * first line of it, so it is not sent — a page of 25 samples would otherwise be half a
 * megabyte of text nobody is reading. {@link AnalysisSampleDetailResponse} carries it when
 * one is actually opened, which is the same split the mailbox makes for the same reason.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisSampleResponse(
        Long id,
        /** MAILBOX or PASTED. */
        String source,
        /** The message it was captured from, if that message is still in the mailbox. */
        Long mailMessageId,
        String fromAddress,
        String fromName,
        String subject,
        LocalDateTime sentAt,
        LocalDateTime receivedAt,
        /** First line or so of the body, for the list. */
        String snippet,
        String attachmentNames,
        AnalysisLabel label,
        AnalysisStatus status,
        /** Whether a target output has been written — the expensive half of a sample. */
        boolean annotated,
        /** How much text this sample would contribute. Long ones are worth a second look. */
        int bodyChars,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
