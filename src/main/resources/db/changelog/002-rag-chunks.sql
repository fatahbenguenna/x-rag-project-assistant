--liquibase formatted sql

--changeset xrag:002-rag-chunks
-- Chunks vectorisés de toutes les sources. Clé stable "source:path:chunk_index"
-- pour permettre l'upsert incrémental (règle : jamais de destruction d'index).
CREATE TABLE rag_chunks (
    id            TEXT PRIMARY KEY,                   -- "source:path:chunk_index"
    source        TEXT NOT NULL,                      -- confluence | gitlab-code | gitlab-mr | jira | project-sheet
    project       TEXT,                               -- id canonique du projet (filtre métadonnées)
    path          TEXT NOT NULL,                      -- page id, chemin fichier, iid MR, clé issue...
    chunk_index   INT  NOT NULL DEFAULT 0,
    title         TEXT,
    content       TEXT NOT NULL,
    url           TEXT,
    metadata      JSONB NOT NULL DEFAULT '{}',
    node_ids      TEXT[] NOT NULL DEFAULT '{}',       -- pont RAG <-> graphe
    embedding     vector(1024),                       -- bge-m3
    tsv           tsvector GENERATED ALWAYS AS
                    (to_tsvector('french', coalesce(title, '') || ' ' || content)) STORED,
    indexed_version TEXT,                             -- version Confluence / SHA git / updated_at source
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE rag_chunks;

--changeset xrag:002-rag-chunks-indexes
CREATE INDEX rag_chunks_embedding_hnsw ON rag_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX rag_chunks_tsv_gin        ON rag_chunks USING gin (tsv);
CREATE INDEX rag_chunks_node_ids_gin   ON rag_chunks USING gin (node_ids);
CREATE INDEX rag_chunks_project_idx    ON rag_chunks (project);
CREATE INDEX rag_chunks_source_path    ON rag_chunks (source, path);
--rollback DROP INDEX rag_chunks_embedding_hnsw; DROP INDEX rag_chunks_tsv_gin; DROP INDEX rag_chunks_node_ids_gin; DROP INDEX rag_chunks_project_idx; DROP INDEX rag_chunks_source_path;
