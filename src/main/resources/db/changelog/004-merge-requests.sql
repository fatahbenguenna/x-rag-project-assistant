--liquibase formatted sql

--changeset xrag:004-merge-requests
-- Métadonnées MR structurées pour les questions factuelles via tools
-- (tris, comptages, "quelle MR ouverte est la plus vieille ?").
-- Le RAG seul répond mal à ces questions.
CREATE TABLE merge_requests (
    id            TEXT PRIMARY KEY,      -- "gitlab:<project_id>:<iid>"
    project       TEXT NOT NULL,         -- id canonique du projet
    iid           BIGINT NOT NULL,
    title         TEXT NOT NULL,
    description   TEXT,
    state         TEXT NOT NULL,         -- opened | merged | closed | locked
    author        TEXT,
    source_branch TEXT,
    target_branch TEXT,
    web_url       TEXT,
    labels        TEXT[] NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,           -- curseur de sync incrémentale (updated_after)
    merged_at     TIMESTAMPTZ
);
CREATE INDEX merge_requests_state_created_idx ON merge_requests (state, created_at);
CREATE INDEX merge_requests_project_idx       ON merge_requests (project);
CREATE INDEX merge_requests_updated_idx       ON merge_requests (updated_at);
--rollback DROP TABLE merge_requests;
