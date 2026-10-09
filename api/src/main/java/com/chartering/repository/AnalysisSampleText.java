package com.chartering.repository;

/**
 * The three columns an embedding is computed from, and nothing else.
 *
 * <p>Loaded by constructor expression rather than as entities, because the embedding pass reads
 * every READY sample's text on each status poll and each run. A full entity would drag its
 * attachments, its notes and its provenance along for a question that needs an id, a subject and
 * a body.
 */
public record AnalysisSampleText(Long id, String subject, String bodyText) {
}
