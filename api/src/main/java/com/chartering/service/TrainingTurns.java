package com.chartering.service;

import com.chartering.model.AnalysisSample;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/**
 * The one definition of what a training turn looks like, shared by the corpus export and by the
 * few-shot retrieval that puts examples into a live request.
 *
 * <p><b>Why this is a class of its own.</b> The export writes a sample as a user turn and an
 * assistant turn; the few-shot path writes the same two turns into the request the model is
 * sent. If the two each had their own copy of the layout, the first change to one of them would
 * quietly teach the model a different shape from the one it is shown at inference - the failure
 * the export's own comments warn about, and one no test that does not look at the bytes would
 * catch. So both call these methods, and {@code AnalysisExportPromptTest} pins the export's output.
 *
 * <p>Deliberately pure layout: the body is passed in as given. The export passes the stored body
 * untouched; the live path trims and caps its body itself, as it always has. Trimming here would
 * change the export's bytes.
 */
public final class TrainingTurns {

    private TrainingTurns() {
    }

    /**
     * The email as the model was trained to read it: Date, Subject, a blank line, the body.
     *
     * <p>The date is the day, not the timestamp - nothing in a circular resolves to an hour - and
     * it is omitted when unknown, so a message with no date does not gain a made-up one.
     */
    public static String userTurn(String subject, LocalDateTime when, String body) {
        StringBuilder sb = new StringBuilder();
        if (when != null) {
            sb.append("Date: ").append(when.toLocalDate()).append('\n');
        }
        if (subject != null && !subject.isBlank()) {
            sb.append("Subject: ").append(subject.strip()).append('\n');
        }
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(body == null ? "" : body);
        return sb.toString();
    }

    /**
     * The user turn for a corpus sample.
     *
     * <p>Sent before received: the sender's own clock is the one the "07/10 SEPTEMBER" in the body
     * was written against, and a message that sat in a queue overnight would otherwise be dated a
     * day after the laycan it announces. Received is the fallback, for mail that carried no Date.
     */
    public static String userTurn(AnalysisSample s) {
        LocalDateTime when = s.getSentAt() != null ? s.getSentAt() : s.getReceivedAt();
        return userTurn(s.getSubject(), when, s.getBodyText());
    }

    /**
     * The assistant turn for a corpus sample: the annotation re-parsed and re-serialised compactly.
     *
     * <p>Re-parsed rather than pasted in, so whatever whitespace a reviewer typed does not become
     * something the model is taught to reproduce, and an annotation that somehow got past
     * validation cannot break the line it is written on.
     */
    public static String assistantContent(String annotation, ObjectMapper json)
            throws JsonProcessingException {
        return json.writeValueAsString(json.readTree(annotation));
    }
}
