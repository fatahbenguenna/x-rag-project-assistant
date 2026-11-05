--liquibase formatted sql

--changeset xrag:006-sync-state-failure
-- Un premier run en échec doit rester visible au dashboard (statut ERREUR) sans poser de
-- curseur : last_sync NULL = jamais synchronisé avec succès, la sync suivante repart de zéro.
ALTER TABLE sync_state ALTER COLUMN last_sync DROP NOT NULL;
--rollback UPDATE sync_state SET last_sync = now() WHERE last_sync IS NULL; ALTER TABLE sync_state ALTER COLUMN last_sync SET NOT NULL;
