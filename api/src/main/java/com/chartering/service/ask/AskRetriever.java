package com.chartering.service.ask;

import com.chartering.service.SampleEmbeddingStore;
import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the passages a question is answered from: the desk's chunks (V39) asked two ways, fused.
 *
 * <p><b>Hybrid, because each half fails alone.</b> The vector half finds a passage that says the
 * same thing in other words - "open tonnage Adriatic" against "SPOT ADRIATIC 1/3 SEPT" - and cannot
 * tell two similar hulls or two similar names apart. The keyword half finds the exact name, number or
 * port and misses a paraphrase. Both are asked for {@link #CANDIDATES} passages and the lists are
 * fused by {@link SearchFusion}.
 *
 * <p><b>Two steps, and only the winners' text is read.</b> The two ranked queries return chunk ids
 * and the record each belongs to, which is all fusion needs. The text is then read for the
 * {@link #TOP} passages that were kept, in one query. The candidates are never loaded whole: thirty
 * passages from each side is up to sixty kilobytes of text per question, most of it discarded.
 *
 * <p><b>Mail is visible to its owner and to colleagues only where the desk shares it.</b> A chunk of
 * a mail message is returned to the asker when they own that mailbox, or when the message is the
 * source of something the desk shares - a cargo, a position, an Intake question, a corpus sample.
 * That is the rule {@code MailMessageRepository.isSharedSource} applies to opening a message, written
 * out here in SQL, so an answer cannot quote an email the asker could not open. Every other kind is
 * the whole desk's.
 *
 * <p>Both queries are restricted to the configured model, so one passage is found once, from the
 * vector set that was computed for it.
 */
@Component
@RequiredArgsConstructor
public class AskRetriever {

    /** Candidates asked for from each side before fusion. */
    static final int CANDIDATES = 30;

    /** Passages from one record the answer may draw on. See {@link SearchFusion}. */
    static final int PER_RECORD = 2;

    /** Passages in the answer. Eight is what the model reads comfortably in a short context. */
    static final int TOP = 8;

    /**
     * One passage the answer may cite.
     *
     * @param score the fused score; larger is closer to the question
     */
    public record Hit(long chunkId, SourceKind kind, long sourceId, String title,
                      LocalDateTime date, String content, double score) {
    }

    /**
     * The visibility rule, as a predicate over an alias {@code c} on {@code search_chunks}. One
     * placeholder: the asker's user id.
     */
    private static final String VISIBLE = """
            (c.source_kind <> 'MAIL' or c.owner_user_id = ? or exists (
                select 1 from cargo_sources s where s.mail_message_id = c.source_id and s.tenant_id = c.tenant_id)
              or exists (select 1 from cargoes x
                    where x.source_mail_message_id = c.source_id and x.tenant_id = c.tenant_id)
              or exists (select 1 from vessel_positions p
                    where p.source_mail_message_id = c.source_id and p.tenant_id = c.tenant_id)
              or exists (select 1 from intake_item_sources i
                    where i.mail_message_id = c.source_id and i.tenant_id = c.tenant_id)
              or exists (select 1 from intake_items it join parsed_emails pe on pe.id = it.parsed_email_id
                    where pe.mail_message_id = c.source_id and it.tenant_id = c.tenant_id)
              or exists (select 1 from analysis_samples a
                    where a.mail_message_id = c.source_id and a.tenant_id = c.tenant_id))
            """;

    private final JdbcTemplate jdbc;

    /**
     * The passages for one question, best first.
     *
     * @param question the question as asked, for the keyword half
     * @param query    its embedding, with the query prefix already applied by the caller
     * @param model    the embedding model the vector was made with
     * @param userId   the asker, for mail visibility
     */
    @Transactional(readOnly = true)
    public List<Hit> search(String question, float[] query, String model, long userId) {
        Long tenant = TenantContext.require();
        List<SearchFusion.Candidate> byVector = vectorCandidates(tenant, model, userId, query);
        List<SearchFusion.Candidate> byWords = keywordCandidates(tenant, model, userId, question);
        List<SearchFusion.Fused> kept = SearchFusion.fuse(List.of(byVector, byWords), PER_RECORD, TOP);
        if (kept.isEmpty()) return List.of();

        List<Long> ids = kept.stream().map(f -> f.candidate().chunkId()).toList();
        Map<Long, Hit> texts = loadTexts(tenant, userId, ids);
        List<Hit> hits = new ArrayList<>(kept.size());
        for (SearchFusion.Fused f : kept) {
            Hit h = texts.get(f.candidate().chunkId());
            if (h != null) hits.add(new Hit(h.chunkId(), h.kind(), h.sourceId(), h.title(), h.date(),
                    h.content(), f.score()));
        }
        return hits;
    }

    private List<SearchFusion.Candidate> vectorCandidates(Long tenant, String model, long userId, float[] query) {
        String sql = """
                select c.id, c.source_kind, c.source_id from search_chunks c
                where c.tenant_id = ? and c.model = ? and %s
                order by c.embedding <=> CAST(? AS vector)
                limit ?
                """.formatted(VISIBLE);
        return jdbc.query(sql, (rs, i) -> candidate(rs),
                tenant, model, userId, SampleEmbeddingStore.toVectorLiteral(query), CANDIDATES);
    }

    private List<SearchFusion.Candidate> keywordCandidates(Long tenant, String model, long userId, String question) {
        String sql = """
                select c.id, c.source_kind, c.source_id from search_chunks c
                where c.tenant_id = ? and c.model = ? and %s
                  and c.tsv @@ websearch_to_tsquery('simple', ?)
                order by ts_rank_cd(c.tsv, websearch_to_tsquery('simple', ?)) desc
                limit ?
                """.formatted(VISIBLE);
        return jdbc.query(sql, (rs, i) -> candidate(rs),
                tenant, model, userId, question, question, CANDIDATES);
    }

    private Map<Long, Hit> loadTexts(Long tenant, long userId, List<Long> ids) {
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.add(userId);
        args.addAll(ids);
        List<Hit> rows = jdbc.query("""
                select c.id, c.source_kind, c.source_id, c.title, c.source_date, c.content
                from search_chunks c
                where c.tenant_id = ? and %s and c.id in (%s)
                """.formatted(VISIBLE, marks), (rs, i) -> {
            Timestamp shown = rs.getTimestamp("source_date");
            return new Hit(rs.getLong("id"), SourceKind.valueOf(rs.getString("source_kind")),
                    rs.getLong("source_id"), rs.getString("title"),
                    shown == null ? null : shown.toLocalDateTime(), rs.getString("content"), 0.0);
        }, args.toArray());
        Map<Long, Hit> byId = new HashMap<>();
        for (Hit h : rows) byId.put(h.chunkId(), h);
        return byId;
    }

    private static SearchFusion.Candidate candidate(ResultSet rs) throws SQLException {
        return new SearchFusion.Candidate(rs.getLong("id"),
                SourceKind.valueOf(rs.getString("source_kind")), rs.getLong("source_id"));
    }
}
