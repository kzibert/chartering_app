package com.chartering.service.ask;

import com.chartering.service.SampleEmbeddingStore;
import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The chunks of the desk's text (V40): written by the indexer, and the questions the indexer asks
 * of them before it reads anything.
 *
 * <p>Plain JDBC, for the reason {@link SampleEmbeddingStore} is: nothing in the object model holds
 * these rows, they are a machine's reading written in bulk, and the desk is named in the SQL
 * itself with {@code tenant_id} written explicitly. The row-level security policy underneath is the
 * same wall for anything this class gets wrong.
 *
 * <p><b>The "what needs doing" questions answer with ids, never with text.</b> The database is
 * billed for data sent out (CLAUDE.md, "Database transfer"), and a pass that loaded every mail body
 * to find the one that changed would send the whole mailbox to find a handful of new messages. So
 * each question here returns the ids of the sources to index, and the text of those sources is read
 * afterwards, in batches, only for the ones that need it. Mail and posts are immutable once stored,
 * so "has no chunk for this model" is the whole test for them. Cargoes and positions can change, so
 * they are also pending when their {@code updated_at} has moved past the one their chunks were made
 * from - compared in SQL, never by reading the text.
 *
 * <p><b>Which mail is indexed is decided here, once.</b> {@link #eligibleMail} excludes the desk's
 * Junk and Trash folders, identified by IMAP SPECIAL-USE (never by name, since the names are in the
 * mailbox's own language). It is used both to find what to index and to prune what should no longer
 * be there, so the two cannot disagree about a message.
 */
@Component
@RequiredArgsConstructor
public class SearchChunkStore {

    /** One chunk, ready to write. The vector is the embedding of {@code content} as the indexer sent it. */
    public record Chunk(int chunkNo, Long ownerUserId, LocalDateTime sourceDate,
                        OffsetDateTime sourceUpdatedAt, String title, String content, float[] vector) {
    }

    private final JdbcTemplate jdbc;

    /**
     * Replaces every chunk of one document, for one model, in one transaction. The delete and the
     * inserts land together or not at all, so a question never sees a document half-replaced.
     */
    @Transactional
    public void replaceChunks(SourceKind kind, long sourceId, String model, List<Chunk> chunks) {
        Long tenant = TenantContext.require();
        jdbc.update("""
                delete from search_chunks
                where tenant_id = ? and source_kind = ? and source_id = ? and model = ?
                """, tenant, kind.name(), sourceId, model);
        for (Chunk c : chunks) {
            jdbc.update("""
                    insert into search_chunks
                        (tenant_id, source_kind, source_id, chunk_no, owner_user_id, source_date,
                         source_updated_at, title, content, model, embedding)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
                    """,
                    tenant, kind.name(), sourceId, c.chunkNo(), c.ownerUserId(),
                    c.sourceDate() == null ? null : Timestamp.valueOf(c.sourceDate()),
                    c.sourceUpdatedAt() == null ? null : Timestamp.from(c.sourceUpdatedAt().toInstant()),
                    c.title(), c.content(), model,
                    SampleEmbeddingStore.toVectorLiteral(c.vector()));
        }
    }

    /**
     * Up to {@code limit} ids of the desk's sources of one kind that have no current chunks for
     * {@code model}. Ids only: the text is loaded later, for the batch being indexed.
     */
    public List<Long> pendingIds(SourceKind kind, String model, int limit) {
        Long tenant = TenantContext.require();
        String sql = switch (kind) {
            case MAIL -> """
                    select m.id from mail_messages m
                    where m.tenant_id = ?
                      and not exists (select 1 from search_chunks c
                            where c.tenant_id = m.tenant_id and c.source_kind = 'MAIL'
                              and c.source_id = m.id and c.model = ?)
                      and %s
                    order by m.id limit ?
                    """.formatted(eligibleMail("m"));
            case POST -> """
                    select i.id from feed_items i
                    where exists (select 1 from feed_intake_subscriptions x
                            where x.tenant_id = ? and x.feed_source_id = i.source_id)
                      and i.text is not null and i.text <> ''
                      and not exists (select 1 from search_chunks c
                            where c.tenant_id = ? and c.source_kind = 'POST'
                              and c.source_id = i.id and c.model = ?)
                    order by i.id limit ?
                    """;
            case CARGO -> """
                    select c.id from cargoes c
                    left join (select source_id, max(source_updated_at) as seen from search_chunks
                               where tenant_id = ? and source_kind = 'CARGO' and model = ?
                               group by source_id) k on k.source_id = c.id
                    where c.tenant_id = ?
                      and (k.source_id is null or coalesce(c.updated_at, c.created_at) > k.seen)
                    order by c.id limit ?
                    """;
            case POSITION -> """
                    select p.id from vessel_positions p
                    left join (select source_id, max(source_updated_at) as seen from search_chunks
                               where tenant_id = ? and source_kind = 'POSITION' and model = ?
                               group by source_id) k on k.source_id = p.id
                    where p.tenant_id = ?
                      and (k.source_id is null
                           or coalesce(p.updated_at, p.created_at, p.reported_at) > k.seen)
                    order by p.id limit ?
                    """;
        };
        return switch (kind) {
            case MAIL -> jdbc.queryForList(sql, Long.class, tenant, model, limit);
            case POST -> jdbc.queryForList(sql, Long.class, tenant, tenant, model, limit);
            case CARGO, POSITION -> jdbc.queryForList(sql, Long.class, tenant, model, tenant, limit);
        };
    }

    /**
     * Removes chunks whose source has gone, or stopped being indexable: a deleted cargo, a message
     * since moved to Junk, a post from a board the desk no longer reads. Local to the database, so
     * it costs no transfer; it runs before each indexing pass so a stale passage cannot be answered
     * from.
     */
    @Transactional
    public int pruneGone() {
        Long tenant = TenantContext.require();
        int removed = 0;
        removed += jdbc.update("""
                delete from search_chunks c where c.tenant_id = ? and c.source_kind = 'MAIL'
                  and not exists (select 1 from mail_messages m
                        where m.id = c.source_id and m.tenant_id = c.tenant_id and %s)
                """.formatted(eligibleMail("m")), tenant);
        removed += jdbc.update("""
                delete from search_chunks c where c.tenant_id = ? and c.source_kind = 'POST'
                  and not exists (select 1 from feed_items i
                        where i.id = c.source_id
                          and i.text is not null and i.text <> ''
                          and exists (select 1 from feed_intake_subscriptions x
                                where x.tenant_id = c.tenant_id and x.feed_source_id = i.source_id))
                """, tenant);
        removed += jdbc.update("""
                delete from search_chunks c where c.tenant_id = ? and c.source_kind = 'CARGO'
                  and not exists (select 1 from cargoes x where x.id = c.source_id and x.tenant_id = c.tenant_id)
                """, tenant);
        removed += jdbc.update("""
                delete from search_chunks c where c.tenant_id = ? and c.source_kind = 'POSITION'
                  and not exists (select 1 from vessel_positions x
                        where x.id = c.source_id and x.tenant_id = c.tenant_id)
                """, tenant);
        return removed;
    }

    /** Removes this desk's chunks made by any other model. Only after a run that reached the end. */
    public int deleteOtherModels(String model) {
        return jdbc.update("delete from search_chunks where tenant_id = ? and model <> ?",
                TenantContext.require(), model);
    }

    /** How many of the desk's sources have chunks for {@code model}. */
    public long indexedSources(String model) {
        Long n = jdbc.queryForObject("""
                select count(*) from (
                    select distinct source_kind, source_id from search_chunks
                    where tenant_id = ? and model = ?) s
                """, Long.class, TenantContext.require(), model);
        return n == null ? 0 : n;
    }

    /**
     * The mail the desk indexes: any message that is not in the Junk or Trash folder of the mailbox
     * it belongs to. The alias is the mail_messages table's alias in the calling query.
     *
     * <p>Matched on the folder's name in the mirror, with the owner's own mirror of its folders. A
     * message whose folder is not in the mirror yet (the first sync has not reached it) counts as
     * eligible, which is the safe way round: the prune pass removes it again once the folder is known
     * to be Junk.
     */
    static String eligibleMail(String alias) {
        return """
                not exists (select 1 from mail_server_folders f
                    where f.tenant_id = %1$s.tenant_id
                      and f.owner_user_id is not distinct from %1$s.owner_user_id
                      and f.full_name = %1$s.imap_folder
                      and f.special_use in ('JUNK', 'TRASH'))
                """.formatted(alias);
    }
}
