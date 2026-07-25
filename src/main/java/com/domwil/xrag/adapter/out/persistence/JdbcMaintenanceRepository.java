package com.domwil.xrag.adapter.out.persistence;

import com.domwil.xrag.domain.port.MaintenanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class JdbcMaintenanceRepository implements MaintenanceRepository {

    private final JdbcTemplate jdbc;

    public JdbcMaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int purgeOrphanNodes() {
        return jdbc.update("""
                DELETE FROM graph_nodes n
                WHERE NOT EXISTS (SELECT 1 FROM graph_edges e WHERE e.src = n.id OR e.dst = n.id)
                  AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id])
                  AND NOT EXISTS (SELECT 1 FROM entity_aliases a WHERE a.node_id = n.id)
                """);
    }

    @Override
    public int purgeUnreferencedTopics() {
        // Un TOPIC n'est jamais atteint par purgeOrphanNodes (arête vers le projet + alias
        // créés systématiquement) : on le purge dès qu'aucun chunk ne le référence. Alias
        // et arêtes d'abord (FK graph_edges -> graph_nodes).
        jdbc.update("""
                DELETE FROM entity_aliases a WHERE a.node_id IN (
                    SELECT n.id FROM graph_nodes n WHERE n.type = 'TOPIC'
                      AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id]))
                """);
        jdbc.update("""
                DELETE FROM graph_edges e WHERE e.src IN (
                    SELECT n.id FROM graph_nodes n WHERE n.type = 'TOPIC'
                      AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id]))
                   OR e.dst IN (
                    SELECT n.id FROM graph_nodes n WHERE n.type = 'TOPIC'
                      AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id]))
                """);
        return jdbc.update("""
                DELETE FROM graph_nodes n WHERE n.type = 'TOPIC'
                  AND NOT EXISTS (SELECT 1 FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id])
                """);
    }

    @Override
    public int dehubTopicEdges() {
        // (a) l'étoile TOPIC -> PROJECT disparaît…
        jdbc.update("""
                DELETE FROM graph_edges e USING graph_nodes n
                WHERE e.src = n.id AND n.type = 'TOPIC' AND e.dst LIKE 'project:%'
                """);
        // (b) …remplacée par TOPIC -> document (page/issue/class), dérivée des node_ids des
        // chunks (le doc et ses topics cohabitent dans node_ids). EXISTS : jamais d'arête
        // vers un nœud fantôme (FK).
        return jdbc.update("""
                INSERT INTO graph_edges (src, dst, type)
                SELECT DISTINCT t.nid, d.nid, 'REFERENCES'
                FROM rag_chunks c
                CROSS JOIN LATERAL unnest(c.node_ids) t(nid)
                CROSS JOIN LATERAL unnest(c.node_ids) d(nid)
                WHERE t.nid LIKE 'topic:%' AND d.nid NOT LIKE 'topic:%' AND d.nid NOT LIKE 'project:%'
                  AND EXISTS (SELECT 1 FROM graph_nodes gn WHERE gn.id = d.nid)
                  -- un topic sans alias est neutralisé (df élevé) : pas d'arête, sinon la
                  -- neutralisation la resupprimerait chaque nuit (churn + compteur trompeur)
                  AND EXISTS (SELECT 1 FROM entity_aliases a WHERE a.node_id = t.nid)
                ON CONFLICT DO NOTHING
                """);
    }

    @Override
    public int purgeNoisyTopics() {
        // (a) PURGE des topics au slug hors latin ÉTENDU — l'extracteur accepte les accents
        // FR (\\p{IsLatin}) : la purge doit les accepter aussi, sinon topic:sécurité serait
        // créé puis détruit dans le même batch (churn LLM infini — revue adversariale) — l'id
        // est retiré des node_ids des chunks pour ne pas fausser le ratio de rattachement.
        jdbc.update("""
                UPDATE rag_chunks SET node_ids = (
                    SELECT coalesce(array_agg(nid), '{}'::text[])
                    FROM unnest(node_ids) nid
                    WHERE nid NOT IN (SELECT id FROM graph_nodes WHERE type='TOPIC' AND id !~ '^topic:[a-z0-9à-ÿœ]+$'))
                WHERE EXISTS (
                    SELECT 1 FROM unnest(node_ids) nid
                    JOIN graph_nodes n ON n.id = nid
                    WHERE n.type='TOPIC' AND n.id !~ '^topic:[a-z0-9à-ÿœ]+$')
                """);
        jdbc.update("DELETE FROM entity_aliases WHERE node_id IN "
                + "(SELECT id FROM graph_nodes WHERE type='TOPIC' AND id !~ '^topic:[a-z0-9à-ÿœ]+$')");
        jdbc.update("DELETE FROM graph_edges WHERE src IN "
                + "(SELECT id FROM graph_nodes WHERE type='TOPIC' AND id !~ '^topic:[a-z0-9à-ÿœ]+$') OR dst IN "
                + "(SELECT id FROM graph_nodes WHERE type='TOPIC' AND id !~ '^topic:[a-z0-9à-ÿœ]+$')");
        int purged = jdbc.update("DELETE FROM graph_nodes WHERE type='TOPIC' AND id !~ '^topic:[a-z0-9à-ÿœ]+$'");

        // (b) NEUTRALISATION des topics génériques (>= 40 documents) : sans alias ils ne
        // sont plus jamais des graines de détection, sans arêtes ils ne polluent plus le
        // voisinage — mais le nœud reste dans node_ids : l'enrichissement voit le document
        // comme déjà traité et ne les recrée pas (pas de cycle purge/re-création).
        int neutralized = jdbc.update("""
                DELETE FROM entity_aliases WHERE node_id IN (
                    SELECT n.id FROM graph_nodes n
                    WHERE n.type='TOPIC'
                      AND (SELECT count(DISTINCT c.source || ':' || c.path)
                           FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id]) >= 40)
                """);
        jdbc.update("""
                DELETE FROM graph_edges e WHERE EXISTS (
                    SELECT 1 FROM graph_nodes n
                    WHERE n.id IN (e.src, e.dst) AND n.type='TOPIC'
                      AND (SELECT count(DISTINCT c.source || ':' || c.path)
                           FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id]) >= 40)
                """);
        // (c) restauration AUTO-RÉPARATRICE : un topic légitime (df < 40) privé d'alias —
        // par un ancien seuil trop agressif ou une purge manuelle — le retrouve. Mesuré :
        // le seuil initial 10 neutralisait « multitenant » (df=16) et faisait chuter le
        // recall du cas multi-tenant (rang 1 -> 5) ; les vrais hubs sémantiques sont >= 40
        // (frontend df=112, angular 64, i18n 47, backend 45).
        jdbc.update("""
                INSERT INTO entity_aliases (alias, node_id)
                SELECT substring(n.id from 7), n.id FROM graph_nodes n
                WHERE n.type = 'TOPIC' AND n.id ~ '^topic:[a-z0-9à-ÿœ]+$'
                  AND NOT EXISTS (SELECT 1 FROM entity_aliases a WHERE a.node_id = n.id)
                  AND (SELECT count(DISTINCT c.source || ':' || c.path)
                       FROM rag_chunks c WHERE c.node_ids @> ARRAY[n.id]) < 40
                ON CONFLICT DO NOTHING
                """);
        return purged + neutralized;
    }

    @Override
    public void vacuumAnalyze() {
        // Hors transaction (autocommit JdbcTemplate). Index HNSW conservé, jamais reconstruit.
        jdbc.execute("VACUUM ANALYZE rag_chunks");
        jdbc.execute("VACUUM ANALYZE graph_nodes");
        jdbc.execute("VACUUM ANALYZE graph_edges");
        jdbc.execute("VACUUM ANALYZE merge_requests");
    }

    @Override
    public Map<String, Object> stats() {
        var stats = new LinkedHashMap<String, Object>();
        jdbc.query("SELECT source, count(*) AS n FROM rag_chunks GROUP BY source ORDER BY source",
                rs -> {
                    stats.put("chunks." + rs.getString("source"), rs.getLong("n"));
                });
        stats.put("graph.nodes", jdbc.queryForObject("SELECT count(*) FROM graph_nodes", Long.class));
        stats.put("graph.edges", jdbc.queryForObject("SELECT count(*) FROM graph_edges", Long.class));
        stats.put("mergeRequests", jdbc.queryForObject("SELECT count(*) FROM merge_requests", Long.class));
        return stats;
    }
}
