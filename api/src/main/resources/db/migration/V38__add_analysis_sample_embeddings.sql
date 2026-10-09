-- One embedding per labelled corpus sample, for retrieved few-shot examples.
--
-- The parser can be shown the labelled emails most like the one it is reading, as examples in
-- its prompt. Finding "most like" needs a vector per sample, and this table holds it beside the
-- sample rather than on it: analysis_samples is the human's work (text, label, annotation), and
-- an embedding is a machine's reading of that text that can be thrown away and recomputed. A
-- column on the sample would make every re-embed a write to an audited-by-hand working document.
--
-- CREATE EXTENSION needs the extension to be available to this database. On Neon it is; where
-- an administrator installed it first, IF NOT EXISTS makes this a no-op. The test database does
-- the same (IntegrationTest), because the application role cannot create extensions.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE public.analysis_sample_embeddings (
    -- One vector per sample. Cascades: a sample deleted takes its vector with it, and nothing
    -- else reads this table without the sample it describes.
    sample_id    bigint PRIMARY KEY REFERENCES public.analysis_samples (id) ON DELETE CASCADE,

    -- The desk, as every desk table has it (V31). No default, on purpose: a write that forgot
    -- whose it is fails rather than landing on desk 1.
    tenant_id    bigint NOT NULL REFERENCES public.tenants (id),

    -- Which embedding model produced the vector. Vectors from two models are not comparable,
    -- so retrieval filters on this, and changing models means re-embedding the corpus.
    model        varchar(200) NOT NULL,

    -- SHA-256 (hex) of the exact text that was embedded. A sample whose text has changed since
    -- is re-embedded; an unchanged one is skipped.
    content_hash varchar(64) NOT NULL,

    -- No dimension and no ANN index, deliberately. The corpus is a few thousand rows per desk,
    -- and an exact scan over that is milliseconds, so an index would only add a build step and
    -- a recall trade-off to think about. Leaving the dimension open means a different embedding
    -- model (a different width) is a re-embed rather than a migration. Column-level, the cost
    -- is that pgvector cannot index it - which is fine at this size.
    embedding    vector NOT NULL,

    created_at   timestamp NOT NULL DEFAULT now()
);

-- Retrieval and the re-embed check both ask for one desk's rows under one model.
CREATE INDEX ix_analysis_sample_embeddings_tenant_model
    ON public.analysis_sample_embeddings (tenant_id, model);

-- The same wall V36 put round every desk table: the database refuses another desk's rows even
-- for plain SQL. A new table is empty, so no app.rls_bypass line is needed.
ALTER TABLE public.analysis_sample_embeddings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.analysis_sample_embeddings FORCE ROW LEVEL SECURITY;
CREATE POLICY desk_rows ON public.analysis_sample_embeddings
    USING (current_setting('app.rls_bypass', true) = 'on'
           OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (current_setting('app.rls_bypass', true) = 'on'
           OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::bigint);
