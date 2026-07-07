--liquibase formatted sql

--changeset xrag:003-graph-nodes
-- Le graphe de connaissances vit dans Postgres (pas de Neo4j).
-- Voisinage profondeur 2 via WITH RECURSIVE.
CREATE TABLE graph_nodes (
    id    TEXT PRIMARY KEY,        -- "project:easyloc", "page:12345", "topic:orders"
    type  TEXT NOT NULL,           -- PROJECT, PAGE, MR, ISSUE, CLASS, TABLE, TOPIC, ENDPOINT
    name  TEXT NOT NULL,
    props JSONB DEFAULT '{}'
);
CREATE INDEX graph_nodes_type_idx ON graph_nodes (type);
CREATE INDEX graph_nodes_name_idx ON graph_nodes (lower(name));
--rollback DROP TABLE graph_nodes;

--changeset xrag:003-graph-edges
CREATE TABLE graph_edges (
    src   TEXT NOT NULL REFERENCES graph_nodes (id) ON DELETE CASCADE,
    dst   TEXT NOT NULL REFERENCES graph_nodes (id) ON DELETE CASCADE,
    type  TEXT NOT NULL,           -- DEPENDS_ON, CALLS_API, SHARES_TABLE, PUBLISHES, CONSUMES,
                                   -- DOCUMENTS, MODIFIES, REFERENCES, LINKS_TO
    props JSONB DEFAULT '{}',
    PRIMARY KEY (src, dst, type)
);
CREATE INDEX graph_edges_dst_idx ON graph_edges (dst);
--rollback DROP TABLE graph_edges;

--changeset xrag:003-entity-aliases
-- Résolution d'entités : "Easy Loc" / easy-loc / EASYLOC -> nœud canonique.
-- Alimentée depuis team-config.yml (aliases) au démarrage. Critique : sans
-- résolution, le graphe se fragmente.
CREATE TABLE entity_aliases (
    alias   TEXT PRIMARY KEY,      -- forme normalisée (minuscules, sans séparateurs)
    node_id TEXT NOT NULL,         -- id canonique ("project:easyloc")
    display TEXT                   -- forme d'origine, pour affichage
);
CREATE INDEX entity_aliases_node_idx ON entity_aliases (node_id);
--rollback DROP TABLE entity_aliases;
