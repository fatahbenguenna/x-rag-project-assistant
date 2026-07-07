--liquibase formatted sql

--changeset xrag:001-pgvector
CREATE EXTENSION IF NOT EXISTS vector;
--rollback DROP EXTENSION IF EXISTS vector;
