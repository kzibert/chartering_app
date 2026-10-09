package com.chartering.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One email to retrieve worked examples for, as the evaluation harness describes it.
 *
 * <p>Every knob is optional so the harness can send only what it is varying. The values left out
 * are the installation's own few-shot settings, which is the point: an unspecified request measures
 * what production would send.
 *
 * @param subject          the subject as the parser would be given it
 * @param sentAt           the sender's clock, or null when the email carries none
 * @param body             the body text
 * @param k                examples wanted, 0 to 8; null means the setting
 * @param maxChars         character budget for the examples; null means the setting
 * @param excludeSampleIds samples never to offer, normally the one being scored
 * @param minDistance      neighbours closer than this are dropped, to keep out a re-sent copy of the
 *                         same circular; null means zero
 */
public record AnalysisFewShotRequest(
        String subject,
        LocalDateTime sentAt,
        String body,
        Integer k,
        Integer maxChars,
        List<Long> excludeSampleIds,
        Double minDistance) {
}
