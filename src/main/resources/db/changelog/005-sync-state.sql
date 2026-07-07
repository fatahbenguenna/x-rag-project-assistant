--liquibase formatted sql

--changeset xrag:005-sync-state
-- Curseur de synchronisation incrémentale par source.
CREATE TABLE sync_state (
    source      TEXT PRIMARY KEY,        -- confluence | gitlab-code | jira | gitlab-mr
    last_sync   TIMESTAMPTZ NOT NULL,
    last_status TEXT
);
--rollback DROP TABLE sync_state;
