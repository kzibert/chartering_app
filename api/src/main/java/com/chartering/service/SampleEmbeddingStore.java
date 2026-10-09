package com.chartering.service;

import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The vector beside each labelled corpus sample (V38), and the nearest-neighbour question the
 * parser asks of it.
 *
 * <p>Plain JDBC rather than a JPA entity, because nothing in the application's object model
 * holds these rows: they are a machine's reading of a sample's text, written in bulk by an
 * embedding pass and read by one query. An entity would also need an entry in TenantScopeTest's
 * list of global tables, or a {@code @TenantId} it cannot carry for a vector column.
 *
 * <p>Because Hibernate never writes these rows, the desk is named in the SQL itself, as
 * {@code DataChangeWriter} names it: {@code tenant_id} is taken from {@link TenantContext} and
 * written explicitly, and every read says {@code tenant_id = ?}. The row-level security policy
 * (V36) underneath enforces the same line for anything this class gets wrong - a missing
 * predicate here is a query that returns nothing from another desk rather than a leak.
 *
 * <p>Vectors travel as their text form, {@code [0.1,0.2,…]}, cast to {@code vector} in SQL. No
 * pgvector Java type is needed, so the PostgreSQL driver is the only dependency this brings.
 */
@Component
@RequiredArgsConstructor
public class SampleEmbeddingStore {

    /** One candidate sample and its cosine distance from the query (0 = same direction). */
    public record Neighbour(long sampleId, double distance) {
    }

    private final JdbcTemplate jdbc;

    /**
     * Stores or replaces the vector for one sample. A sample whose text changed is re-embedded
     * with its new hash, so a row here always describes the text the hash names.
     */
    public void upsert(long sampleId, String model, String contentHash, float[] embedding) {
        Long tenant = TenantContext.require();
        // Selected from the sample on this desk rather than VALUES: a desk's tenant_id on a
        // vector of another desk's sample would pass the policy (it checks the row's own
        // tenant_id) and would embed text this desk was never given. The select finds nothing
        // for a sample it cannot see, so nothing is written, and that is reported.
        int written = jdbc.update("""
                insert into analysis_sample_embeddings
                    (sample_id, tenant_id, model, content_hash, embedding)
                select s.id, s.tenant_id, ?, ?, CAST(? AS vector)
                from analysis_samples s
                where s.id = ? and s.tenant_id = ?
                on conflict (sample_id) do update set
                    tenant_id = excluded.tenant_id,
                    model = excluded.model,
                    content_hash = excluded.content_hash,
                    embedding = excluded.embedding,
                    created_at = now()
                """,
                model, contentHash, toVectorLiteral(embedding), sampleId, tenant);
        if (written == 0) {
            throw new IllegalArgumentException("analysis sample " + sampleId + " is not on this desk");
        }
    }

    /**
     * Sample id to the hash of the text it was embedded from, for this desk and model. The
     * embedding pass compares it with the current hashes to find what is new or changed.
     */
    public Map<Long, String> hashesFor(String model) {
        return jdbc.query("""
                select sample_id, content_hash from analysis_sample_embeddings
                where tenant_id = ? and model = ?
                """,
                (rs, i) -> Map.entry(rs.getLong("sample_id"), rs.getString("content_hash")),
                TenantContext.require(), model)
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The {@code k} labelled samples nearest the query, nearest first. Only READY samples are
     * candidates: a sample nobody has judged fit to train on is not a fit example to show a
     * model either. Vectors from another model are ignored, since their distances mean nothing
     * here.
     */
    public List<Neighbour> nearest(float[] query, String model, int k, Collection<Long> excludeSampleIds) {
        String literal = toVectorLiteral(query);
        Long tenant = TenantContext.require();

        StringBuilder sql = new StringBuilder("""
                select e.sample_id, e.embedding <=> CAST(? AS vector) as distance
                from analysis_sample_embeddings e
                join analysis_samples s on s.id = e.sample_id
                where e.tenant_id = ?
                  and s.tenant_id = e.tenant_id
                  and e.model = ?
                  and s.status = 'READY'
                """);
        Object[] args;
        if (excludeSampleIds == null || excludeSampleIds.isEmpty()) {
            args = new Object[]{literal, tenant, model, k};
        } else {
            sql.append("  and e.sample_id not in (")
                    .append(String.join(",", Collections.nCopies(excludeSampleIds.size(), "?")))
                    .append(")\n");
            args = new Object[3 + excludeSampleIds.size() + 1];
            args[0] = literal;
            args[1] = tenant;
            args[2] = model;
            int i = 3;
            for (Long id : excludeSampleIds) args[i++] = id;
            args[i] = k;
        }
        sql.append("order by distance limit ?\n");

        return jdbc.query(sql.toString(),
                (rs, i) -> new Neighbour(rs.getLong("sample_id"), rs.getDouble("distance")),
                args);
    }

    /**
     * Removes this desk's vectors made by any other model. Run after a model change once the new
     * model's vectors are in place, so retrieval never has two generations to choose between.
     */
    public int deleteOtherModels(String model) {
        return jdbc.update(
                "delete from analysis_sample_embeddings where tenant_id = ? and model <> ?",
                TenantContext.require(), model);
    }

    /** How many of this desk's samples have a vector from {@code model}. */
    public long count(String model) {
        Long n = jdbc.queryForObject(
                "select count(*) from analysis_sample_embeddings where tenant_id = ? and model = ?",
                Long.class, TenantContext.require(), model);
        return n == null ? 0 : n;
    }

    /**
     * {@code [0.1,0.2,…]}. Float.toString is exact enough for a round trip through pgvector's
     * float4 storage. NaN and infinities are refused here: pgvector would reject them with a
     * message about the literal, and a model that produced one has a bug worth naming.
     */
    static String toVectorLiteral(float[] v) {
        if (v == null || v.length == 0) throw new IllegalArgumentException("embedding is empty");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (!Float.isFinite(v[i])) {
                throw new IllegalArgumentException("embedding component " + i + " is not finite: " + v[i]);
            }
            if (i > 0) sb.append(',');
            sb.append(Float.toString(v[i]));
        }
        return sb.append(']').toString();
    }
}
