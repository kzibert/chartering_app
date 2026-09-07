package com.chartering.service;

import com.chartering.model.AnalysisSample;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The corpus written out as a file a finetuning job can read.
 *
 * <p><b>JSONL, one example per line, in the chat shape.</b> Not because one vendor asked for
 * it, but because every one of them accepts a conversation of system/user/assistant turns —
 * OpenAI's, Anthropic's, and the open-source trainers around Llama and Mistral all read this
 * or a mechanical rewrite of it. A columnar or CSV export would have to be reshaped into
 * this before anything could train on it, and the reshaping is where the escaping goes wrong.
 *
 * <p><b>The system prompt is the same on every line and is the one a caller could really
 * send.</b> That is the whole trick of a usable finetune: what the model is trained to do
 * has to be what it will be asked to do. A prompt that named the answer ("extract the cargo
 * from this cargo offer") would train a model that only works when somebody has already done
 * the classifying — which is the job.
 *
 * <p><b>The user turn is the email as it arrived</b>, subject and date included and headers
 * otherwise left out. The subject carries real signal in this domain (a broker's whole offer
 * is often in it) and the rest — Received chains, MIME boundaries, DKIM — is machinery that
 * would teach the model to read plumbing.
 *
 * <p><b>The date is there because the annotations carry years and the emails do not.</b> A
 * circular says "OPEN 07/10 SEPTEMBER" and never the year; the annotation has to say
 * 2026-09-07, because a date with no year cannot be compared to a laycan and every timing
 * check against it comes back UNKNOWN. Train that pairing without showing the model the
 * message date and the year is learnable from nothing on the page — it would be copying
 * whichever years happened to be in the training set. A real caller always has the date of
 * the message it is passing in, so putting it in the user turn costs a line and is the
 * difference between a resolvable laycan and a guess.
 *
 * <p>Only {@code READY} samples are written, and they are written in id order so that
 * regenerating an export produces the same file byte for byte. Diffing two exports is how a
 * labelling session's work is actually seen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisExportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final AnalysisService analysis;
    private final ObjectMapper json;

    /** What the browser should call the downloaded file. Stamped, because a corpus grows. */
    public String filename() {
        return "chartering-training-" + java.time.LocalDateTime.now().format(STAMP) + ".jsonl";
    }

    /**
     * The whole file as one string.
     *
     * <p>Built in memory rather than streamed, and that is a deliberate limit rather than an
     * oversight: a corpus is thousands of emails of a few kilobytes each, so the file is tens
     * of megabytes at the outside, and it is downloaded by one person clicking once. If it
     * ever stops being that, the fix is a {@code StreamingResponseBody} over the same loop —
     * not a paging scheme, because a training file that arrives in pages is not a training
     * file.
     */
    public String toJsonl() {
        List<AnalysisSample> ready = analysis.readyForExport();
        StringBuilder out = new StringBuilder(ready.size() * 4096);
        for (AnalysisSample s : ready) {
            out.append(line(s)).append('\n');
        }
        log.info("Analysis export: {} examples", ready.size());
        return out.toString();
    }

    /**
     * One example.
     *
     * <p>The annotation is re-parsed rather than pasted in as a string, so the assistant turn
     * is the JSON object itself re-serialised compactly and canonically. Two consequences,
     * both wanted: whatever whitespace and indentation a reviewer typed does not become
     * something the model is taught to reproduce, and a sample whose annotation somehow got
     * past validation cannot emit a line that breaks the file.
     */
    private String line(AnalysisSample s) {
        try {
            ObjectNode root = json.createObjectNode();
            ArrayNode msgs = root.putArray("messages");

            msgs.addObject()
                    .put("role", "system")
                    .put("content", AnalysisAnnotationTemplates.SYSTEM_PROMPT);
            msgs.addObject()
                    .put("role", "user")
                    .put("content", userTurn(s));
            msgs.addObject()
                    .put("role", "assistant")
                    .put("content", json.writeValueAsString(json.readTree(s.getAnnotation())));

            // Not part of the training turns — trainers ignore unknown keys, and this is what
            // lets an example in a file be traced back to the row it came from when it turns
            // out to be the one teaching the model something wrong.
            ObjectNode meta = root.putObject("metadata");
            meta.put("sampleId", s.getId());
            meta.put("label", s.getLabel().name());
            if (s.getFromAddress() != null) meta.put("from", s.getFromAddress());
            if (s.getReceivedAt() != null) meta.put("receivedAt", s.getReceivedAt().toString());

            return json.writeValueAsString(root);
        } catch (Exception e) {
            // A sample that cannot be serialised is a bug in what was stored, not a reason to
            // hand back half a training file with no explanation.
            throw new IllegalStateException(
                    "Sample " + s.getId() + " could not be exported: " + e.getMessage(), e);
        }
    }

    private static String userTurn(AnalysisSample s) {
        StringBuilder sb = new StringBuilder();
        // Sent before received: the sender's own clock is the one the "07/10 SEPTEMBER" in
        // the body was written against, and a message that sat in a queue overnight would
        // otherwise be dated a day after the laycan it announces. Received is the fallback,
        // for the mail that arrived carrying no Date header at all.
        LocalDateTime when = s.getSentAt() != null ? s.getSentAt() : s.getReceivedAt();
        if (when != null) {
            // The day, not the timestamp. Nothing in a circular resolves to an hour, and a
            // time of day on every line is noise the model would learn to reproduce.
            sb.append("Date: ").append(when.toLocalDate()).append('\n');
        }
        if (s.getSubject() != null && !s.getSubject().isBlank()) {
            sb.append("Subject: ").append(s.getSubject().strip()).append('\n');
        }
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(s.getBodyText());
        return sb.toString();
    }
}
